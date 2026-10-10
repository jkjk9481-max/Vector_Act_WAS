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
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** C11 실패한 분석 재접수 통합 테스트. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:analysisretry;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "auth.withdrawal.purge-enabled=false",
        "script.ocr.purge-enabled=false"})
@AutoConfigureMockMvc
class AnalysisRetryIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired AuthOneTimeTokenRepository oneTimeTokens;
    @Autowired CoachingSessionRepository sessions;
    @Autowired SessionVideoRepository videos;
    @Autowired SessionChunkRepository chunks;
    @Autowired IdempotencyKeyRepository idempotencyKeys;
    @Autowired AnalysisRepository analyses;
    @Autowired AnalysisScoreRepository scores;
    @Autowired AnalysisFeedbackRepository feedbacks;
    @Autowired AccessTokenProvider accessTokens;
    @Autowired JdbcTemplate jdbc;
    private User user;
    private String bearer;

    @BeforeEach
    void prepare() {
        feedbacks.deleteAll();
        scores.deleteAll();
        analyses.deleteAll();
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

    /** 원본 영상이 READY이고 분석이 FAILED(시도 1)인 세션을 만듭니다. */
    private CoachingSession failedSession(UUID userId) {
        var now = OffsetDateTime.now();
        var session = CoachingSession.prepare(userId, "대본", "상황", true, false, false,
                CoachingSession.Intensity.NORMAL, now);
        session = sessions.saveAndFlush(session);
        jdbc.update("update coaching_sessions set status = 'COMPLETED', video_status = 'READY', "
                + "analysis_status = 'FAILED', analysis_attempt = 1, failure_code = 'AI_TIMEOUT' where id = ?",
                session.getId());
        var analysis = Analysis.queued(userId, session.getId(), now);
        analysis.fail("AI_TIMEOUT", now);
        analyses.saveAndFlush(analysis);
        return session;
    }

    private ResultActions retry(UUID sessionId, String key) throws Exception {
        var request = post("/api/coaching-sessions/" + sessionId + "/analysis-retries")
                .header("Authorization", bearer);
        if (key != null) request.header("Idempotency-Key", key);
        return mvc.perform(request);
    }

    private static String key() { return UUID.randomUUID().toString(); }

    @Test
    void requeuesFailedAnalysisWithNextAttempt() throws Exception {
        var session = failedSession(user.getId());

        retry(session.getId(), key()).andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.attempt").value(2))
                .andExpect(jsonPath("$.data.analysisStatus").value("QUEUED"));

        var analysis = analyses.findBySessionId(session.getId()).orElseThrow();
        assertThat(analysis.getStatus()).isEqualTo(Analysis.Status.QUEUED);
        assertThat(analysis.getAttempt()).isEqualTo(2);
        assertThat(analysis.getFailureCode()).isNull();
        var saved = sessions.findById(session.getId()).orElseThrow();
        assertThat(saved.getAnalysisStatus()).isEqualTo(CoachingSession.AnalysisStatus.QUEUED);
        assertThat(saved.getAnalysisAttempt()).isEqualTo(2);
        assertThat(saved.getFailureCode()).isNull();
    }

    @Test
    void sameKeyReplaysWithoutIncreasingAttempt() throws Exception {
        var session = failedSession(user.getId());
        String key = key();
        retry(session.getId(), key).andExpect(status().isAccepted());

        retry(session.getId(), key).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.attempt").value(2));

        assertThat(analyses.findBySessionId(session.getId()).orElseThrow().getAttempt()).isEqualTo(2);
    }

    @Test
    void sameKeyForAnotherSessionIsIdempotencyConflict() throws Exception {
        var first = failedSession(user.getId());
        String key = key();
        retry(first.getId(), key).andExpect(status().isAccepted());
        jdbc.update("update coaching_sessions set status = 'CANCELED' where id = ?", first.getId());
        var second = failedSession(user.getId());

        retry(second.getId(), key).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void newKeyWhileRunningIsAlreadyRunning() throws Exception {
        var session = failedSession(user.getId());
        retry(session.getId(), key()).andExpect(status().isAccepted());

        retry(session.getId(), key()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ANALYSIS_ALREADY_RUNNING"));
    }

    @Test
    void thirdFailureCanBeRetriedButFourthAttemptIsNotAllowed() throws Exception {
        var session = failedSession(user.getId());
        retry(session.getId(), key()).andExpect(jsonPath("$.data.attempt").value(2));
        jdbc.update("update analyses set status = 'FAILED'");
        retry(session.getId(), key()).andExpect(jsonPath("$.data.attempt").value(3));
        jdbc.update("update analyses set status = 'FAILED'");

        // 총 3회까지: 세 번째 시도까지 실패한 뒤에는 더 요청할 수 없습니다.
        retry(session.getId(), key()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RETRY_NOT_ALLOWED"));
        assertThat(analyses.findBySessionId(session.getId()).orElseThrow().getAttempt()).isEqualTo(3);
    }

    @Test
    void notFailedAnalysisIsRetryNotAllowed() throws Exception {
        var session = failedSession(user.getId());

        for (String state : new String[]{"COMPLETED", "PARTIAL", "CANCELED"}) {
            jdbc.update("update analyses set status = ?", state);
            retry(session.getId(), key()).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("RETRY_NOT_ALLOWED"));
        }
    }

    @Test
    void videoNotReadyIsRetryNotAllowed() throws Exception {
        var session = failedSession(user.getId());
        jdbc.update("update coaching_sessions set video_status = 'ASSEMBLING'");

        retry(session.getId(), key()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RETRY_NOT_ALLOWED"));
    }

    @Test
    void noAnalysisYetIsRetryNotAllowed() throws Exception {
        var session = sessions.saveAndFlush(CoachingSession.prepare(user.getId(), "대본", "상황", true, false, false,
                CoachingSession.Intensity.NORMAL, OffsetDateTime.now()));

        retry(session.getId(), key()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RETRY_NOT_ALLOWED"));
    }

    @Test
    void expiredVideoIsGone() throws Exception {
        var session = failedSession(user.getId());

        for (String state : new String[]{"EXPIRED", "DELETED"}) {
            jdbc.update("update coaching_sessions set video_status = ?", state);
            retry(session.getId(), key()).andExpect(status().isGone())
                    .andExpect(jsonPath("$.error.code").value("VIDEO_EXPIRED"));
        }
    }

    @Test
    void missingOrMalformedKeyIsValidationError() throws Exception {
        var session = failedSession(user.getId());

        retry(session.getId(), null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        retry(session.getId(), "not-a-uuid").andExpect(status().isBadRequest());
        assertThat(analyses.findBySessionId(session.getId()).orElseThrow().getAttempt()).isEqualTo(1);
    }

    @Test
    void otherUsersOrUnknownOrDeletedSessionIs404() throws Exception {
        var other = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "다른회원"));
        var othersSession = failedSession(other.getId());
        var mine = failedSession(user.getId());
        jdbc.update("update coaching_sessions set deleted_at = CURRENT_TIMESTAMP where id = ?", mine.getId());

        for (UUID id : new UUID[]{othersSession.getId(), UUID.randomUUID(), mine.getId()}) {
            retry(id, key()).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        }
        assertThat(analyses.findBySessionId(othersSession.getId()).orElseThrow().getAttempt()).isEqualTo(1);
    }

    @Test
    void requiresBearerToken() throws Exception {
        var session = failedSession(user.getId());

        mvc.perform(post("/api/coaching-sessions/" + session.getId() + "/analysis-retries")
                        .header("Idempotency-Key", key()))
                .andExpect(status().isUnauthorized());
    }
}
