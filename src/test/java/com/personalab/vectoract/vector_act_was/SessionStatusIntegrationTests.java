package com.personalab.vectoract.vector_act_was;

import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.*;
import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import com.personalab.vectoract.vector_act_was.global.auth.AccessTokenProvider;
import com.personalab.vectoract.vector_act_was.global.idempotency.IdempotencyKeyRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** C09 세션 상태 조회 통합 테스트. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sessionstatus;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "auth.withdrawal.purge-enabled=false",
        "script.ocr.purge-enabled=false"})
@AutoConfigureMockMvc
class SessionStatusIntegrationTests {
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

    private CoachingSession created(UUID userId) {
        return sessions.saveAndFlush(CoachingSession.prepare(userId, "대본", "상황", true, false, false,
                CoachingSession.Intensity.NORMAL, OffsetDateTime.now()));
    }

    private ResultActions fetch(UUID sessionId) throws Exception {
        return mvc.perform(get("/api/coaching-sessions/" + sessionId + "/status").header("Authorization", bearer));
    }

    @Test
    void returnsInitialStateOfPreparedSession() throws Exception {
        var session = created(user.getId());

        fetch(session.getId()).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sessionId").value(session.getId().toString()))
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andExpect(jsonPath("$.data.videoStatus").value("NOT_STARTED"))
                .andExpect(jsonPath("$.data.analysisStatus").value("NOT_STARTED"))
                .andExpect(jsonPath("$.data.analysisMode").value("REALTIME"))
                .andExpect(jsonPath("$.data.missingChunkIndexes.length()").value(0))
                .andExpect(jsonPath("$.data.uploadDeadlineAt").value((Object) null))
                .andExpect(jsonPath("$.data.analysisAttempt").value(0))
                .andExpect(jsonPath("$.data.failureCode").value((Object) null));
    }

    @Test
    void reflectsRecordingState() throws Exception {
        var session = created(user.getId());
        session.startRecording(OffsetDateTime.now());
        sessions.saveAndFlush(session);

        fetch(session.getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RECORDING"))
                .andExpect(jsonPath("$.data.videoStatus").value("UPLOADING"))
                .andExpect(jsonPath("$.data.missingChunkIndexes.length()").value(0));
    }

    @Test
    void afterFinishReportsMissingChunksAndDeadline() throws Exception {
        var session = created(user.getId());
        session.startRecording(OffsetDateTime.now());
        session.finish(2, 15000, OffsetDateTime.now());
        sessions.saveAndFlush(session);
        var verified = SessionChunk.reserve(session.getId(), 1, 5000, 10000, 1000, SHA,
                "session-chunks/" + session.getId() + "/1-" + UUID.randomUUID(),
                OffsetDateTime.now(), OffsetDateTime.now().plusMinutes(5));
        verified.markVerified(OffsetDateTime.now());
        chunks.saveAndFlush(verified);

        fetch(session.getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FINALIZING"))
                .andExpect(jsonPath("$.data.uploadDeadlineAt").isNotEmpty())
                // 0~2 중 VERIFIED는 1번뿐입니다.
                .andExpect(jsonPath("$.data.missingChunkIndexes.length()").value(2))
                .andExpect(jsonPath("$.data.missingChunkIndexes[0]").value(0))
                .andExpect(jsonPath("$.data.missingChunkIndexes[1]").value(2));
    }

    @Test
    void failedSessionShowsFailureCode() throws Exception {
        var session = created(user.getId());
        jdbc.update("update coaching_sessions set status = 'FAILED', analysis_status = 'FAILED', "
                + "failure_code = 'ANALYSIS_FAILED', analysis_attempt = 2");

        fetch(session.getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.analysisStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.analysisAttempt").value(2))
                .andExpect(jsonPath("$.data.failureCode").value("ANALYSIS_FAILED"));
    }

    @Test
    void otherUsersOrUnknownOrDeletedSessionIs404() throws Exception {
        var other = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "다른회원"));
        var othersSession = created(other.getId());
        jdbc.update("update coaching_sessions set status = 'CANCELED' where id = ?", othersSession.getId());
        var mine = created(user.getId());
        jdbc.update("update coaching_sessions set deleted_at = CURRENT_TIMESTAMP where id = ?", mine.getId());

        for (UUID id : new UUID[]{othersSession.getId(), UUID.randomUUID(), mine.getId()}) {
            fetch(id).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        }
    }

    @Test
    void malformedSessionIdIsValidationError() throws Exception {
        mvc.perform(get("/api/coaching-sessions/not-a-uuid/status").header("Authorization", bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void requiresBearerToken() throws Exception {
        var session = created(user.getId());

        mvc.perform(get("/api/coaching-sessions/" + session.getId() + "/status"))
                .andExpect(status().isUnauthorized());
    }
}
