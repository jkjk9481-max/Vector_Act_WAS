package com.personalab.vectoract.vector_act_was;

import com.jayway.jsonpath.JsonPath;
import com.personalab.vectoract.vector_act_was.domain.member.business.ReauthService;
import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import com.personalab.vectoract.vector_act_was.domain.member.presentation.dto.*;
import com.personalab.vectoract.vector_act_was.global.auth.*;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import javax.crypto.spec.SecretKeySpec;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * A09 통합 테스트입니다. 실제 인증 필터·비밀번호 인코더·Service·Repository와 H2 DB를 연결합니다.
 * MockMvc로 HTTP 요청을 보내고, 응답뿐 아니라 DB에 커밋된 상태도 확인합니다.
 * 소비 테스트는 향후 탈퇴 Service의 트랜잭션을 TransactionTemplate으로 대신 만듭니다.
 * 테스트 전체를 @Transactional로 감싸지 않아 실제 커밋/롤백과 동시 사용을 확인할 수 있습니다.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:reauth;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE")
@AutoConfigureMockMvc
class ReauthIntegrationTests {
    // 만료 JWT를 서명할 때와 서버가 검증할 때 동일한 테스트 전용 난수 키를 씁니다.
    private static final byte[] SIGNING_KEY = new byte[32];
    static { new java.security.SecureRandom().nextBytes(SIGNING_KEY); }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("auth.jwt.secret-base64", () -> Base64.getEncoder().encodeToString(SIGNING_KEY));
    }

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired AuthOneTimeTokenRepository tokens;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired RefreshTokenGenerator generator;
    @Autowired AccessTokenProvider accessTokens;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    // 평소에는 실제 Service입니다. 의존 시스템 장애 테스트에서만 예외를 주입합니다.
    @MockitoSpyBean ReauthService service;
    private static final String PASSWORD = "CurrentPassword!1";
    private User user;
    private String access;
    private RefreshToken loginToken;

    @BeforeEach
    void prepare() {
        // 테스트마다 새 회원 ID를 만들어 요청 제한 카운터와 이전 DB 상태에 영향을 받지 않게 합니다.
        tokens.deleteAll();
        refreshTokens.deleteAll();
        users.deleteAll();
        user = users.saveAndFlush(User.create("actor@example.com", passwords.encode(PASSWORD), "회원"));
        user = users.findById(user.getId()).orElseThrow();
        access = accessTokens.issue(user.getId());
        loginToken = refreshTokens.saveAndFlush(RefreshToken.create(user, generator.hash(generator.generate()), OffsetDateTime.now()));
    }

    @Test
    void issuesOwnerBoundFiveMinuteTokenAndStoresOnlyHashWithoutWithdrawing() throws Exception {
        var other = users.saveAndFlush(User.create("other@example.com", passwords.encode(PASSWORD), "다른회원"));
        // 타인 ID를 본문과 쿼리로 보내도 인증 토큰 소유자에게만 발급돼야 합니다.
        String response = mvc.perform(post("/api/auth/reauth").param("userId", other.getId().toString())
                        .header("Authorization", "Bearer " + access).contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("password", PASSWORD, "userId", other.getId().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> data = JsonPath.read(response, "$.data");
        assertThat(data).containsOnlyKeys("reauthToken", "expiresAt");
        String raw = (String) data.get("reauthToken");
        assertThat(raw).matches("[A-Za-z0-9_-]{43}");
        var stored = tokens.findByTokenHash(generator.hash(raw)).orElseThrow();
        assertThat(stored.getTokenHash()).hasSize(64).isNotEqualTo(raw);
        assertThat(stored.getUser().getId()).isEqualTo(user.getId());
        assertThat(stored.getTokenType()).isEqualTo(AuthOneTimeToken.TokenType.REAUTH);
        assertThat(stored.getUsedAt()).isNull();
        assertThat(Duration.between(stored.getCreatedAt(), stored.getExpiresAt())).isEqualTo(Duration.ofMinutes(5));
        // DB는 나노초를 반올림할 수 있으므로 JSON과 저장값은 밀리초 단위로 비교합니다.
        assertThat(OffsetDateTime.parse((String) data.get("expiresAt")).toInstant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
                .isEqualTo(stored.getExpiresAt().toInstant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        assertThat(response).doesNotContain(PASSWORD, stored.getTokenHash());
        assertAccountUnchanged();
    }

    @Test
    void reauthTokenCannotAuthenticateAsAccessOrRefreshToken() throws Exception {
        String raw = issue();
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + raw))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("ACCESS_INVALID"));
        mvc.perform(post("/api/auth/refresh").with(csrf()).cookie(new Cookie("refreshToken", raw)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("REFRESH_INVALID"));
        assertThat(tokens.findByTokenHash(generator.hash(raw)).orElseThrow().getUsedAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"password\":null}", "{\"password\":\"\"}", "{", ""})
    void rejectsMissingEmptyAndMalformedInput(String body) throws Exception {
        send(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        assertNothingIssued();
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-password", "   ", "가가가가가가가가가가가가가가가가가가가가가가가가가"})
    void rejectsIncorrectPasswordWithoutIssuingToken(String password) throws Exception {
        password(password).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CURRENT_PASSWORD_INVALID"));
        assertNothingIssued();
    }

    @Test
    void preservesActualPasswordWhitespace() throws Exception {
        String spacedPassword = " CurrentPassword!1 ";
        jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", passwords.encode(spacedPassword), user.getId());
        password(spacedPassword).andExpect(status().isOk());
        password(spacedPassword.strip()).andExpect(status().isForbidden());
        assertThat(tokens.count()).isEqualTo(1);
    }

    @Test
    void unknownOrWithdrawnUserIsNotFound() throws Exception {
        access = accessTokens.issue(UUID.randomUUID());
        password(PASSWORD).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        access = accessTokens.issue(user.getId());
        jdbc.update("UPDATE users SET account_status = 'WITHDRAWN' WHERE id = ?", user.getId());
        password(PASSWORD).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        assertThat(tokens.count()).isZero();
    }

    @Test
    void bearerIsRequiredEvenWithCsrfOrRefreshCookie() throws Exception {
        mvc.perform(post("/api/auth/reauth").with(csrf()).cookie(new Cookie("refreshToken", "anything"))
                        .contentType("application/json").content(mapper.writeValueAsString(Map.of("password", PASSWORD))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
        assertNothingIssued();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer broken", "Bearer ", "Basic abc"})
    void invalidBearerIsRejected(String authorization) throws Exception {
        mvc.perform(post("/api/auth/reauth").header("Authorization", authorization)
                        .contentType("application/json").content(mapper.writeValueAsString(Map.of("password", PASSWORD))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("ACCESS_INVALID"));
        assertNothingIssued();
    }

    @Test
    void expiredBearerIsRejected() throws Exception {
        // 올바른 키로 과거 만료 시각의 JWT를 서명해 만료 오류를 기다림 없이 확인합니다.
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer("vector-act-test").subject(user.getId().toString())
                .issuedAt(now.minusSeconds(1000)).expiresAt(now.minusSeconds(100)).claim("token_use", "access").build();
        access = NimbusJwtEncoder.withSecretKey(new SecretKeySpec(SIGNING_KEY, "HmacSHA256")).build()
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        password(PASSWORD).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("ACCESS_EXPIRED"));
        assertNothingIssued();
    }

    @Test
    void limitsPasswordGuessingPerUserAndKeepsOtherUsersIndependent() throws Exception {
        for (int i = 0; i < 10; i++) password("wrong-password").andExpect(status().isForbidden());
        password(PASSWORD).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
        assertNothingIssued();
        var other = users.saveAndFlush(User.create("other@example.com", passwords.encode(PASSWORD), "다른회원"));
        access = accessTokens.issue(other.getId());
        password(PASSWORD).andExpect(status().isOk());
    }

    @Test
    void insertFailureDoesNotReturnOrPersistToken() throws Exception {
        // 실제 DB 제약으로 INSERT를 실패시켜 롤백과 500 응답을 확인합니다. finally에서 제약을 제거합니다.
        jdbc.execute("ALTER TABLE auth_one_time_tokens ADD CONSTRAINT a09_insert_failure CHECK (token_type = 'PASSWORD_RESET')");
        try {
            password(PASSWORD).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.data.reauthToken").doesNotExist());
            assertNothingIssued();
        } finally {
            jdbc.execute("ALTER TABLE auth_one_time_tokens DROP CONSTRAINT a09_insert_failure");
        }
    }

    @Test
    void databaseUnavailableReturns503() throws Exception {
        doThrow(new org.springframework.transaction.CannotCreateTransactionException("database unavailable"))
                .when(service).issue(user.getId(), PASSWORD);
        password(PASSWORD).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.data.reauthToken").doesNotExist());
        assertNothingIssued();
    }

    @Test
    void secretsAreRedactedFromDebugStrings() throws Exception {
        String raw = issue();
        var expires = OffsetDateTime.now().plusMinutes(5);
        assertThat(new ReauthRequest(PASSWORD).toString()).doesNotContain(PASSWORD);
        assertThat(new ReauthResponse(raw, expires).toString()).doesNotContain(raw);
        assertThat(new ReauthService.Result(raw, expires).toString()).doesNotContain(raw);
    }

    @Test
    void consumesExactlyOnceAndPreservesFirstUseTime() throws Exception {
        String raw = issue();
        consume(user.getId(), raw);
        var used = tokens.findByTokenHash(generator.hash(raw)).orElseThrow().getUsedAt();
        assertThat(used).isNotNull();
        assertInvalid(() -> consume(user.getId(), raw));
        assertThat(tokens.findByTokenHash(generator.hash(raw)).orElseThrow().getUsedAt()).isEqualTo(used);
    }

    @Test
    void expiredAndWrongTokenTypeTokensCannotBeUsed() throws Exception {
        String expired = issue();
        jdbc.update("UPDATE auth_one_time_tokens SET expires_at = ? WHERE token_hash = ?", OffsetDateTime.now().minusSeconds(1), generator.hash(expired));
        assertInvalid(() -> consume(user.getId(), expired));
        String reset = issue();
        jdbc.update("UPDATE auth_one_time_tokens SET token_type = 'PASSWORD_RESET' WHERE token_hash = ?", generator.hash(reset));
        assertInvalid(() -> consume(user.getId(), reset));
        assertThat(tokens.findAll()).allSatisfy(token -> assertThat(token.getUsedAt()).isNull());
    }

    @Test
    void exactExpirationBoundaryIsNotUsable() {
        // 현재 시각과 expires_at이 정확히 같은 경계도 사용 불가여야 합니다.
        var now = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        var token = tokens.saveAndFlush(AuthOneTimeToken.createReauth(user, generator.hash(generator.generate()), now.minusMinutes(5)));
        Integer updated = new TransactionTemplate(transactionManager).execute(status -> tokens.consumeIfUsable(
                token.getTokenHash(), user.getId(), AuthOneTimeToken.TokenType.REAUTH, now));
        assertThat(updated).isZero();
    }

    @Test
    void anotherUserCannotConsumeOwnersToken() throws Exception {
        String raw = issue();
        var other = users.saveAndFlush(User.create("other@example.com", passwords.encode(PASSWORD), "다른회원"));
        assertInvalid(() -> consume(other.getId(), raw));
        assertThat(tokens.findByTokenHash(generator.hash(raw)).orElseThrow().getUsedAt()).isNull();
        consume(user.getId(), raw);
    }

    @Test
    void withdrawnUserCannotConsumePreviouslyIssuedToken() throws Exception {
        String raw = issue();
        jdbc.update("UPDATE users SET account_status = 'WITHDRAWN' WHERE id = ?", user.getId());
        assertThatThrownBy(() -> consume(user.getId(), raw)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        assertThat(tokens.findByTokenHash(generator.hash(raw)).orElseThrow().getUsedAt()).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "unknown", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void missingMalformedOrUnknownTokenCannotBeConsumed(String raw) {
        assertInvalid(() -> consume(user.getId(), raw));
    }

    @Test
    void consumingRequiresCallersTransaction() throws Exception {
        String raw = issue();
        assertThatThrownBy(() -> service.consumeForWithdrawal(user.getId(), raw))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(tokens.findByTokenHash(generator.hash(raw)).orElseThrow().getUsedAt()).isNull();
    }

    @Test
    void withdrawalFailureRollsBackTokenConsumption() throws Exception {
        String raw = issue();
        // 실제 탈퇴 저장이 실패한 상황을 가정해 같은 트랜잭션에서 예외를 발생시킵니다.
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.consumeForWithdrawal(user.getId(), raw);
            throw new IllegalStateException("simulated withdrawal failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(tokens.findByTokenHash(generator.hash(raw)).orElseThrow().getUsedAt()).isNull();
        consume(user.getId(), raw);
    }

    @Test
    void simultaneousConsumptionHasExactlyOneWinner() throws Exception {
        String raw = issue();
        var start = new CountDownLatch(1);
        // 두 스레드를 같은 신호로 출발시킵니다. 성공은 딱 한 번이어야 합니다.
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> attempt = () -> {
                start.await();
                try { consume(user.getId(), raw); return true; }
                catch (BusinessException error) {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.ACCESS_INVALID);
                    return false;
                }
            };
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(tokens.findByTokenHash(generator.hash(raw)).orElseThrow().getUsedAt()).isNotNull();
    }

    // 반복되는 HTTP 요청/트랜잭션/DB 검증을 모은 보조 메서드들입니다.
    private ResultActions password(String password) throws Exception {
        return send(mapper.writeValueAsString(Map.of("password", password)));
    }
    private ResultActions send(String body) throws Exception {
        // 명세대로 Bearer만 보냅니다. CSRF 토큰 없이도 이 보호 API의 인증이 작동해야 합니다.
        return mvc.perform(post("/api/auth/reauth").header("Authorization", "Bearer " + access)
                .contentType("application/json").content(body));
    }
    private String issue() throws Exception {
        String response = password(PASSWORD).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.data.reauthToken");
    }
    private void consume(UUID userId, String raw) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> service.consumeForWithdrawal(userId, raw));
    }
    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.ACCESS_INVALID));
    }
    private void assertNothingIssued() {
        assertThat(tokens.count()).isZero();
        assertAccountUnchanged();
    }
    private void assertAccountUnchanged() {
        var saved = users.findById(user.getId()).orElseThrow();
        assertThat(saved.getPasswordHash()).isEqualTo(user.getPasswordHash());
        assertThat(saved.getAccountStatus()).isEqualTo(User.AccountStatus.ACTIVE);
        assertThat(saved.getUpdatedAt()).isEqualTo(user.getUpdatedAt());
        assertThat(saved.getDeletedAt()).isNull();
        assertThat(saved.getPurgeAt()).isNull();
        assertThat(refreshTokens.findById(loginToken.getId()).orElseThrow().getRevokedAt()).isNull();
    }
}
