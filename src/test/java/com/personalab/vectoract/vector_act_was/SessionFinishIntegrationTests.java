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
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** C07 촬영 종료·최종화 접수 통합 테스트. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sessionfinish;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "auth.withdrawal.purge-enabled=false",
        "script.ocr.purge-enabled=false"})
@AutoConfigureMockMvc
class SessionFinishIntegrationTests {
    private static final String SHA = "a".repeat(64);
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

    private CoachingSession recording(UUID userId) {
        var now = OffsetDateTime.now();
        var session = CoachingSession.prepare(userId, "대본", "상황", true, false, false,
                CoachingSession.Intensity.NORMAL, now);
        session.startRecording(now);
        return sessions.saveAndFlush(session);
    }

    private void chunk(CoachingSession session, int index, boolean verified) {
        var c = SessionChunk.reserve(session.getId(), index, index * 5000, index * 5000 + 5000, 1000, SHA,
                "session-chunks/" + session.getId() + "/" + index + "-" + UUID.randomUUID(),
                OffsetDateTime.now(), OffsetDateTime.now().plusMinutes(5));
        if (verified) c.markVerified(OffsetDateTime.now());
        chunks.saveAndFlush(c);
    }

    private static String body(int lastChunkIndex, int durationMs) {
        return "{\"lastChunkIndex\":" + lastChunkIndex + ",\"durationMs\":" + durationMs + "}";
    }

    private ResultActions finish(UUID sessionId, String body, String key) throws Exception {
        var request = post("/api/coaching-sessions/" + sessionId + "/finish")
                .contentType(MediaType.APPLICATION_JSON).content(body).header("Authorization", bearer);
        if (key != null) request.header("Idempotency-Key", key);
        return mvc.perform(request);
    }

    private static String key() { return UUID.randomUUID().toString(); }

    @Test
    void acceptsFinishAndReportsMissingChunks() throws Exception {
        var session = recording(user.getId());
        chunk(session, 0, true);
        chunk(session, 2, false);

        finish(session.getId(), body(3, 20000), key()).andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sessionId").value(session.getId().toString()))
                .andExpect(jsonPath("$.data.status").value("FINALIZING"))
                .andExpect(jsonPath("$.data.videoStatus").value("UPLOADING"))
                .andExpect(jsonPath("$.data.analysisStatus").value("NOT_STARTED"))
                .andExpect(jsonPath("$.data.analysisMode").value("REALTIME"))
                // 0~3 중 VERIFIED는 0번뿐: 1(미선언), 2(RESERVED), 3(미선언)
                .andExpect(jsonPath("$.data.missingChunkIndexes.length()").value(3))
                .andExpect(jsonPath("$.data.missingChunkIndexes[0]").value(1))
                .andExpect(jsonPath("$.data.uploadDeadlineAt").isNotEmpty())
                .andExpect(jsonPath("$.data.analysisAttempt").value(0))
                .andExpect(jsonPath("$.data.failureCode").value((Object) null));

        var saved = sessions.findById(session.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(CoachingSession.Status.FINALIZING);
        assertThat(saved.getEndedAt()).isNotNull();
        assertThat(Duration.between(saved.getEndedAt(), saved.getUploadDeadlineAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(saved.getDeclaredLastChunkIndex()).isEqualTo(3);
        assertThat(saved.getDeclaredDurationMs()).isEqualTo(20000);
    }

    @Test
    void allChunksVerifiedHasEmptyMissing() throws Exception {
        var session = recording(user.getId());
        for (int i = 0; i <= 2; i++) chunk(session, i, true);

        finish(session.getId(), body(2, 15000), key()).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.missingChunkIndexes.length()").value(0));
    }

    @Test
    void sameKeyAndBodyReplaysFirstResponse() throws Exception {
        var session = recording(user.getId());
        String key = key();
        String first = finish(session.getId(), body(1, 10000), key).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        // 재시도 사이에 청크가 검증돼도 처음 응답을 그대로 돌려줍니다.
        chunk(session, 0, true);

        String second = finish(session.getId(), body(1, 10000), key).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(idempotencyKeys.count()).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentBodyIsIdempotencyConflict() throws Exception {
        var session = recording(user.getId());
        String key = key();
        finish(session.getId(), body(1, 10000), key).andExpect(status().isAccepted());

        finish(session.getId(), body(2, 10000), key).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void newKeyWithSameManifestIsAcceptedAgainWithoutChangingDeadline() throws Exception {
        var session = recording(user.getId());
        finish(session.getId(), body(1, 10000), key()).andExpect(status().isAccepted());
        var firstDeadline = sessions.findById(session.getId()).orElseThrow().getUploadDeadlineAt();

        finish(session.getId(), body(1, 10000), key()).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("FINALIZING"));

        assertThat(sessions.findById(session.getId()).orElseThrow().getUploadDeadlineAt())
                .isEqualTo(firstDeadline);
    }

    @Test
    void changedManifestAfterFinishIsManifestConflict() throws Exception {
        var session = recording(user.getId());
        finish(session.getId(), body(1, 10000), key()).andExpect(status().isAccepted());

        finish(session.getId(), body(5, 10000), key()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FINISH_MANIFEST_CONFLICT"));
        finish(session.getId(), body(1, 20000), key()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FINISH_MANIFEST_CONFLICT"));
        assertThat(sessions.findById(session.getId()).orElseThrow().getDeclaredLastChunkIndex()).isEqualTo(1);
    }

    @Test
    void chunkBeyondDeclaredLastIndexIsManifestConflict() throws Exception {
        var session = recording(user.getId());
        chunk(session, 5, false);

        finish(session.getId(), body(3, 10000), key()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FINISH_MANIFEST_CONFLICT"));
        assertThat(sessions.findById(session.getId()).orElseThrow().getStatus())
                .isEqualTo(CoachingSession.Status.RECORDING);
    }

    @Test
    void notRecordingOrFinalizingIsStateConflict() throws Exception {
        var session = recording(user.getId());

        for (String state : new String[]{"CREATED", "COMPLETED", "FAILED", "CANCELED"}) {
            jdbc.update("update coaching_sessions set status = ?", state);
            finish(session.getId(), body(1, 10000), key()).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("SESSION_STATE_CONFLICT"));
        }
    }

    @Test
    void invalidInputIsValidationError() throws Exception {
        var session = recording(user.getId());
        String[] bodies = {body(-1, 10000), body(121, 10000), body(1, 0), body(1, 600001),
                "{\"lastChunkIndex\":1}", "{}", "not-json"};
        for (String b : bodies) {
            finish(session.getId(), b, key()).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
        finish(session.getId(), body(1, 10000), null).andExpect(status().isBadRequest());
        finish(session.getId(), body(1, 10000), "not-a-uuid").andExpect(status().isBadRequest());
    }

    @Test
    void otherUsersOrUnknownOrDeletedSessionIs404() throws Exception {
        var other = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "다른회원"));
        var othersSession = recording(other.getId());
        var mine = recording(user.getId());
        jdbc.update("update coaching_sessions set deleted_at = CURRENT_TIMESTAMP where id = ?", mine.getId());

        for (UUID id : new UUID[]{othersSession.getId(), UUID.randomUUID(), mine.getId()}) {
            finish(id, body(1, 10000), key()).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        }
    }

    @Test
    void requiresBearerToken() throws Exception {
        var session = recording(user.getId());

        mvc.perform(post("/api/coaching-sessions/" + session.getId() + "/finish")
                        .contentType(MediaType.APPLICATION_JSON).content(body(1, 10000))
                        .header("Idempotency-Key", key()))
                .andExpect(status().isUnauthorized());
    }
}
