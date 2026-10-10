package com.personalab.vectoract.vector_act_was;

import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.*;
import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import com.personalab.vectoract.vector_act_was.global.auth.AccessTokenProvider;
import com.personalab.vectoract.vector_act_was.global.idempotency.IdempotencyKeyRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** C04 Chunk 업로드 URL 발급 통합 테스트. 응답(HTTP)과 DB의 청크 예약 상태를 함께 확인합니다. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:chunkupload;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "auth.withdrawal.purge-enabled=false",
        "script.ocr.purge-enabled=false"})
@AutoConfigureMockMvc
class ChunkUploadUrlIntegrationTests {
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);
    private static final String WEBM = "video/webm;codecs=vp8,opus";
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired AuthOneTimeTokenRepository oneTimeTokens;
    @Autowired CoachingSessionRepository sessions;
    @Autowired SessionVideoRepository videos;
    @Autowired SessionChunkRepository chunks;
    @Autowired IdempotencyKeyRepository idempotencyKeys;
    @Autowired AccessTokenProvider accessTokens;
    @Autowired JdbcTemplate jdbc;
    private User user;
    private String bearer;

    @BeforeEach
    void prepare() {
        chunks.deleteAll();
        videos.deleteAll();
        idempotencyKeys.deleteAll();
        sessions.deleteAll();
        oneTimeTokens.deleteAll();
        refreshTokens.deleteAll();
        users.deleteAll();
        user = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "회원"));
        bearer = "Bearer " + accessTokens.issue(user.getId());
    }

    /** 촬영 중(RECORDING) 세션과, C02가 남기는 입력 영상 정보를 DB에 바로 만듭니다. */
    private CoachingSession recording(UUID userId) {
        var now = OffsetDateTime.now();
        var session = CoachingSession.prepare(userId, "대본", "상황", true, false, false,
                CoachingSession.Intensity.NORMAL, now);
        session.startRecording(now);
        session = sessions.saveAndFlush(session);
        videos.saveAndFlush(SessionVideo.started(session.getId(), WEBM, 1920, 1080,
                new BigDecimal("30.00"), now, now.plusDays(30)));
        return session;
    }

    private static String body(int startMs, int endMs, Object sizeBytes, String sha256) {
        return "{\"startMs\":" + startMs + ",\"endMs\":" + endMs + ",\"sizeBytes\":" + sizeBytes
                + ",\"sha256\":\"" + sha256 + "\"}";
    }

    private ResultActions issue(UUID sessionId, int chunkIndex, String body) throws Exception {
        return mvc.perform(post("/api/coaching-sessions/" + sessionId + "/chunks/" + chunkIndex + "/upload-url")
                .contentType(MediaType.APPLICATION_JSON).content(body).header("Authorization", bearer));
    }

    @Test
    void reservesChunkAndReturnsUploadUrl() throws Exception {
        var session = recording(user.getId());

        issue(session.getId(), 0, body(0, 5000, 1048576, SHA_A)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.chunkIndex").value(0))
                .andExpect(jsonPath("$.data.putUrl").isNotEmpty())
                .andExpect(jsonPath("$.data.requiredHeaders['content-type']").value(WEBM))
                .andExpect(jsonPath("$.data.expiresAt").isNotEmpty());

        var chunk = chunks.findBySessionIdAndChunkIndex(session.getId(), 0).orElseThrow();
        assertThat(chunk.getStatus()).isEqualTo(SessionChunk.Status.RESERVED);
        assertThat(chunk.getStartMs()).isZero();
        assertThat(chunk.getEndMs()).isEqualTo(5000);
        assertThat(chunk.getSizeBytes()).isEqualTo(1048576L);
        assertThat(chunk.getSha256()).isEqualTo(SHA_A);
        assertThat(chunk.getObjectKey()).startsWith("session-chunks/" + session.getId() + "/0-");
        assertThat(chunk.getExpiresAt()).isNotNull();
    }

    @Test
    void sameContentAgainReusesObjectKeyAndIssuesUrlAgain() throws Exception {
        var session = recording(user.getId());
        issue(session.getId(), 3, body(15000, 20000, 1000, SHA_A)).andExpect(status().isOk());
        String firstKey = chunks.findBySessionIdAndChunkIndex(session.getId(), 3).orElseThrow().getObjectKey();

        issue(session.getId(), 3, body(15000, 20000, 1000, SHA_A)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.putUrl").isNotEmpty());

        assertThat(chunks.count()).isEqualTo(1);
        assertThat(chunks.findBySessionIdAndChunkIndex(session.getId(), 3).orElseThrow().getObjectKey())
                .isEqualTo(firstKey);
    }

    @Test
    void differentContentForSameIndexIsChunkConflict() throws Exception {
        var session = recording(user.getId());
        issue(session.getId(), 1, body(5000, 10000, 1000, SHA_A)).andExpect(status().isOk());

        // 해시가 다른 경우, 크기가 다른 경우, 구간이 다른 경우 모두 충돌입니다.
        for (String conflicting : new String[]{
                body(5000, 10000, 1000, SHA_B), body(5000, 10000, 2000, SHA_A), body(5000, 10001, 1000, SHA_A)}) {
            issue(session.getId(), 1, conflicting).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("CHUNK_CONFLICT"));
        }
        // 기존 예약은 바뀌지 않아야 합니다.
        assertThat(chunks.findBySessionIdAndChunkIndex(session.getId(), 1).orElseThrow().getSha256()).isEqualTo(SHA_A);
    }

    @Test
    void tooLargeSizeIs413AndUpperBoundIsAccepted() throws Exception {
        var session = recording(user.getId());

        issue(session.getId(), 0, body(0, 5000, 33554433L, SHA_A)).andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("FILE_TOO_LARGE"));
        // 5GB처럼 int 범위를 넘는 값도 400이 아니라 413이어야 합니다.
        issue(session.getId(), 0, body(0, 5000, 5_000_000_000L, SHA_A)).andExpect(status().isPayloadTooLarge());
        issue(session.getId(), 0, body(0, 5000, 33554432L, SHA_A)).andExpect(status().isOk());
    }

    @Test
    void invalidInputIsValidationError() throws Exception {
        var session = recording(user.getId());
        String[] bodies = {
                body(-1, 5000, 1000, SHA_A),                 // startMs 음수
                body(5000, 5000, 1000, SHA_A),               // endMs == startMs
                body(6000, 5000, 1000, SHA_A),               // endMs < startMs
                body(0, 600001, 1000, SHA_A),                // endMs 상한 초과
                body(0, 5000, 0, SHA_A),                     // sizeBytes 0
                body(0, 5000, 1000, "A".repeat(64)),         // 대문자 hex
                body(0, 5000, 1000, "a".repeat(63)),         // 길이 부족
                "{\"startMs\":0,\"endMs\":5000,\"sizeBytes\":1000}", // sha256 누락
                "{}", "not-json"};
        for (String body : bodies) {
            issue(session.getId(), 0, body).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
        assertThat(chunks.count()).isZero();
    }

    @Test
    void chunkIndexOutOfRangeIsValidationError() throws Exception {
        var session = recording(user.getId());

        issue(session.getId(), 121, body(0, 5000, 1000, SHA_A)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        issue(session.getId(), -1, body(0, 5000, 1000, SHA_A)).andExpect(status().isBadRequest());
        // 경계값 0과 120은 허용됩니다.
        issue(session.getId(), 120, body(0, 5000, 1000, SHA_A)).andExpect(status().isOk());
    }

    @Test
    void sessionNotRecordingIsStateConflict() throws Exception {
        var session = recording(user.getId());

        for (String state : new String[]{"CREATED", "COMPLETED", "FAILED", "CANCELED"}) {
            jdbc.update("update coaching_sessions set status = ?", state);
            issue(session.getId(), 0, body(0, 5000, 1000, SHA_A)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("SESSION_STATE_CONFLICT"));
        }
        assertThat(chunks.count()).isZero();
    }

    @Test
    void finalizingAllowsUploadUntilDeadlineThenExpired() throws Exception {
        var session = recording(user.getId());
        jdbc.update("update coaching_sessions set status = 'FINALIZING', upload_deadline_at = ?",
                OffsetDateTime.now().plusMinutes(5));
        issue(session.getId(), 0, body(0, 5000, 1000, SHA_A)).andExpect(status().isOk());

        jdbc.update("update coaching_sessions set upload_deadline_at = ?", OffsetDateTime.now().minusSeconds(1));
        issue(session.getId(), 1, body(5000, 10000, 1000, SHA_A)).andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("UPLOAD_EXPIRED"));
    }

    @Test
    void otherUsersOrUnknownOrDeletedSessionIs404() throws Exception {
        var other = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "다른회원"));
        var othersSession = recording(other.getId());
        var mine = recording(user.getId());
        jdbc.update("update coaching_sessions set deleted_at = CURRENT_TIMESTAMP where id = ?", mine.getId());

        for (UUID id : new UUID[]{othersSession.getId(), UUID.randomUUID(), mine.getId()}) {
            issue(id, 0, body(0, 5000, 1000, SHA_A)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        }
        assertThat(chunks.count()).isZero();
    }

    @Test
    void malformedPathIsValidationError() throws Exception {
        mvc.perform(post("/api/coaching-sessions/not-a-uuid/chunks/0/upload-url")
                        .contentType(MediaType.APPLICATION_JSON).content(body(0, 5000, 1000, SHA_A))
                        .header("Authorization", bearer))
                .andExpect(status().isBadRequest());
        var session = recording(user.getId());
        mvc.perform(post("/api/coaching-sessions/" + session.getId() + "/chunks/abc/upload-url")
                        .contentType(MediaType.APPLICATION_JSON).content(body(0, 5000, 1000, SHA_A))
                        .header("Authorization", bearer))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requiresBearerToken() throws Exception {
        var session = recording(user.getId());

        mvc.perform(post("/api/coaching-sessions/" + session.getId() + "/chunks/0/upload-url")
                        .contentType(MediaType.APPLICATION_JSON).content(body(0, 5000, 1000, SHA_A)))
                .andExpect(status().isUnauthorized());
    }
}
