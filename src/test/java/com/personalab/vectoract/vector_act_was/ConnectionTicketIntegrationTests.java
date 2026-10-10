package com.personalab.vectoract.vector_act_was;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.*;
import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import com.personalab.vectoract.vector_act_was.global.auth.AccessTokenProvider;
import com.personalab.vectoract.vector_act_was.global.idempotency.IdempotencyKeyRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * C03 연결 티켓 발급 통합 테스트. 테스트 실행마다 RSA 키쌍을 새로 만들어 개인키만 서버 설정으로 주입하고,
 * 발급된 JWT를 공개키로 직접 검증합니다(AI 서버가 하는 검증과 같은 방식).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:connectionticket;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "auth.withdrawal.purge-enabled=false",
        "script.ocr.purge-enabled=false",
        "coaching.connection-ticket.web-socket-url=wss://example.test",
        // 제한 초과를 적은 횟수로 확인하기 위한 값입니다.
        "coaching.connection-ticket.rate-limit.max-attempts=5"})
@AutoConfigureMockMvc
class ConnectionTicketIntegrationTests {
    private static final KeyPair KEY_PAIR = generate();

    // 컨텍스트가 만들어지기 전에 개인키(PKCS#8 PEM)를 설정으로 넣습니다.
    @DynamicPropertySource
    static void ticketKey(DynamicPropertyRegistry registry) {
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(KEY_PAIR.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
        registry.add("coaching.connection-ticket.private-key", () -> pem);
    }

    private static KeyPair generate() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired AuthOneTimeTokenRepository oneTimeTokens;
    @Autowired CoachingSessionRepository sessions;
    @Autowired SessionVideoRepository videos;
    @Autowired IdempotencyKeyRepository idempotencyKeys;
    @Autowired AccessTokenProvider accessTokens;
    @Autowired JdbcTemplate jdbc;
    private User user;
    private String bearer;

    @BeforeEach
    void prepare() {
        videos.deleteAll();
        idempotencyKeys.deleteAll();
        sessions.deleteAll();
        oneTimeTokens.deleteAll();
        refreshTokens.deleteAll();
        users.deleteAll();
        user = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "회원"));
        bearer = "Bearer " + accessTokens.issue(user.getId());
    }

    /** 촬영 중(RECORDING) 세션을 DB에 바로 만듭니다. */
    private CoachingSession recording(UUID userId) {
        var session = CoachingSession.prepare(userId, "대본", "상황", true, false, false,
                CoachingSession.Intensity.NORMAL, OffsetDateTime.now());
        session.startRecording(OffsetDateTime.now());
        return sessions.saveAndFlush(session);
    }

    private ResultActions issue(UUID sessionId) throws Exception {
        return mvc.perform(post("/api/coaching-sessions/" + sessionId + "/connection-tickets")
                .header("Authorization", bearer));
    }

    @Test
    void issuesSignedTicketForRecordingSession() throws Exception {
        var session = recording(user.getId());

        var result = issue(session.getId()).andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.ticket").isNotEmpty())
                .andExpect(jsonPath("$.data.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.data.webSocketUrl").value("wss://example.test"))
                .andReturn().getResponse().getContentAsString();

        // AI 서버와 같은 방식으로 공개키만 사용해 서명과 클레임을 검증합니다.
        var jwt = SignedJWT.parse(JsonPath.<String>read(result, "$.data.ticket"));
        assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) KEY_PAIR.getPublic()))).isTrue();
        assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
        assertThat(jwt.getHeader().getKeyID()).isNotBlank();
        var claims = jwt.getJWTClaimsSet();
        assertThat(claims.getSubject()).isEqualTo(user.getId().toString());
        assertThat(claims.getStringClaim("sid")).isEqualTo(session.getId().toString());
        assertThat(claims.getAudience()).containsExactly("ai-server");
        assertThat(claims.getJWTID()).isNotBlank();
        // 발급 + 30초 유효.
        assertThat(claims.getExpirationTime().getTime() - claims.getIssueTime().getTime()).isEqualTo(30_000L);
        // 응답의 expiresAt은 JWT의 exp와 같은 시각입니다.
        var expiresAt = OffsetDateTime.parse(JsonPath.<String>read(result, "$.data.expiresAt"));
        assertThat(expiresAt.toInstant()).isEqualTo(claims.getExpirationTime().toInstant());
    }

    @Test
    void everyTicketHasDistinctJti() throws Exception {
        var session = recording(user.getId());

        String first = JsonPath.read(issue(session.getId()).andReturn().getResponse().getContentAsString(),
                "$.data.ticket");
        String second = JsonPath.read(issue(session.getId()).andReturn().getResponse().getContentAsString(),
                "$.data.ticket");

        assertThat(SignedJWT.parse(first).getJWTClaimsSet().getJWTID())
                .isNotEqualTo(SignedJWT.parse(second).getJWTClaimsSet().getJWTID());
    }

    @Test
    void sessionNotRecordingIsStateConflict() throws Exception {
        var session = sessions.saveAndFlush(CoachingSession.prepare(user.getId(), "대본", "상황", true, false, false,
                CoachingSession.Intensity.NORMAL, OffsetDateTime.now()));

        // CREATED(촬영 시작 전)
        issue(session.getId()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SESSION_STATE_CONFLICT"));
        // 종료된 상태들
        for (String ended : new String[]{"FINALIZING", "COMPLETED", "FAILED", "CANCELED"}) {
            jdbc.update("update coaching_sessions set status = ?", ended);
            issue(session.getId()).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("SESSION_STATE_CONFLICT"));
        }
    }

    @Test
    void otherUsersOrUnknownOrDeletedSessionIs404() throws Exception {
        var other = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "다른회원"));
        var othersSession = recording(other.getId());
        jdbc.update("update coaching_sessions set status = 'CANCELED' where id = ?", othersSession.getId());
        var mine = recording(user.getId());
        jdbc.update("update coaching_sessions set deleted_at = CURRENT_TIMESTAMP where id = ?", mine.getId());

        for (UUID id : new UUID[]{othersSession.getId(), UUID.randomUUID(), mine.getId()}) {
            issue(id).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        }
    }

    @Test
    void malformedSessionIdIsValidationError() throws Exception {
        mvc.perform(post("/api/coaching-sessions/not-a-uuid/connection-tickets").header("Authorization", bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void requiresBearerToken() throws Exception {
        var session = recording(user.getId());

        mvc.perform(post("/api/coaching-sessions/" + session.getId() + "/connection-tickets"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void exceedingRateLimitIsRateLimited() throws Exception {
        var session = recording(user.getId());
        for (int i = 0; i < 5; i++) issue(session.getId()).andExpect(status().isCreated());

        issue(session.getId()).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }
}
