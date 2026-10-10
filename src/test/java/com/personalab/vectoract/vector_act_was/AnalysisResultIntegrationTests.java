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
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** C10 분석 결과 조회 통합 테스트. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:analysisresult;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "auth.withdrawal.purge-enabled=false",
        "script.ocr.purge-enabled=false"})
@AutoConfigureMockMvc
class AnalysisResultIntegrationTests {
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

    private CoachingSession session(UUID userId) {
        return sessions.saveAndFlush(CoachingSession.prepare(userId, "대본", "상황", true, false, false,
                CoachingSession.Intensity.NORMAL, OffsetDateTime.now()));
    }

    /** 결과가 저장된 완료 상태의 분석을 만듭니다. 점수 행은 일부 항목만 넣어 누락 항목 보정을 확인합니다. */
    private Analysis completed(CoachingSession session, Analysis.Status status) {
        var now = OffsetDateTime.now();
        var analysis = Analysis.queued(session.getUserId(), session.getId(), now);
        analysis.complete(status, "1.0", "mp-1.lgbm-1", "score-1", 60000, new BigDecimal("78.46"),
                "전반적으로 안정적입니다.", "[\"시선이 안정적입니다\"]", "[\"말 속도를 줄여 보세요\"]",
                "[\"호흡 연습\",\"감정 전환 연습\"]", now);
        analysis = analyses.saveAndFlush(analysis);
        scores.saveAndFlush(AnalysisScore.of(analysis.getId(), "EXPRESSION", AnalysisScore.Status.AVAILABLE,
                new BigDecimal("82.35"), new BigDecimal("0.912"), null, now));
        scores.saveAndFlush(AnalysisScore.of(analysis.getId(), "VOICE", AnalysisScore.Status.UNAVAILABLE,
                null, null, "AUDIO_TOO_SHORT", now));
        scores.saveAndFlush(AnalysisScore.of(analysis.getId(), "POSTURE", AnalysisScore.Status.NOT_SUPPORTED,
                null, null, "NOT_SUPPORTED", now));
        // 시간순과 다르게 저장해도 시간순으로 나와야 합니다.
        feedbacks.saveAndFlush(AnalysisFeedback.of(analysis.getId(), 20000, 25000, "VOICE", "SPEECH_RATE",
                "IMPROVEMENT", "WARNING", "말이 빠릅니다", "천천히 말해 보세요", now));
        feedbacks.saveAndFlush(AnalysisFeedback.of(analysis.getId(), 5000, 9000, "GAZE", "GAZE_HOLD",
                "STRENGTH", "INFO", "시선이 안정적입니다", null, now));
        return analysis;
    }

    private ResultActions fetch(UUID sessionId) throws Exception {
        return mvc.perform(get("/api/coaching-sessions/" + sessionId + "/result").header("Authorization", bearer));
    }

    @Test
    void returnsCompletedResultWithAllSevenScoreItems() throws Exception {
        var session = session(user.getId());
        var analysis = completed(session, Analysis.Status.COMPLETED);

        fetch(session.getId()).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.analysisId").value(analysis.getId().toString()))
                .andExpect(jsonPath("$.data.sessionId").value(session.getId().toString()))
                .andExpect(jsonPath("$.data.source").value("LIVE_CAPTURE"))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.schemaVersion").value("1.0"))
                .andExpect(jsonPath("$.data.modelVersion").value("mp-1.lgbm-1"))
                .andExpect(jsonPath("$.data.scoringVersion").value("score-1"))
                .andExpect(jsonPath("$.data.durationMs").value(60000))
                // 소수 1자리 반올림: 78.46 → 78.5
                .andExpect(jsonPath("$.data.overallScore").value(78.5))
                .andExpect(jsonPath("$.data.summary").value("전반적으로 안정적입니다."))
                .andExpect(jsonPath("$.data.strengths[0]").value("시선이 안정적입니다"))
                .andExpect(jsonPath("$.data.improvements[0]").value("말 속도를 줄여 보세요"))
                .andExpect(jsonPath("$.data.nextPractice.length()").value(2))
                .andExpect(jsonPath("$.data.generatedAt").isNotEmpty())
                // 점수 항목: 있는 항목은 그대로, 82.35 → 82.4
                .andExpect(jsonPath("$.data.scores.expression.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.scores.expression.score").value(82.4))
                .andExpect(jsonPath("$.data.scores.expression.confidence").value(0.912))
                .andExpect(jsonPath("$.data.scores.expression.reasonCode").value((Object) null))
                .andExpect(jsonPath("$.data.scores.voice.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.scores.voice.score").value((Object) null))
                .andExpect(jsonPath("$.data.scores.voice.reasonCode").value("AUDIO_TOO_SHORT"))
                .andExpect(jsonPath("$.data.scores.posture.status").value("NOT_SUPPORTED"))
                // 행이 없는 항목도 7개가 모두 존재해야 합니다.
                .andExpect(jsonPath("$.data.scores.gaze.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.scores.emotion.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.scores.scriptDelivery.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.scores.situationFit.status").value("UNAVAILABLE"))
                // 구간 피드백은 시간순입니다.
                .andExpect(jsonPath("$.data.segments.length()").value(2))
                .andExpect(jsonPath("$.data.segments[0].startMs").value(5000))
                .andExpect(jsonPath("$.data.segments[0].category").value("GAZE"))
                .andExpect(jsonPath("$.data.segments[0].kind").value("STRENGTH"))
                .andExpect(jsonPath("$.data.segments[0].severity").value("INFO"))
                .andExpect(jsonPath("$.data.segments[0].suggestion").value((Object) null))
                .andExpect(jsonPath("$.data.segments[0].feedbackId").isNotEmpty())
                .andExpect(jsonPath("$.data.segments[1].startMs").value(20000))
                .andExpect(jsonPath("$.data.segments[1].suggestion").value("천천히 말해 보세요"));
    }

    @Test
    void partialResultIsReturned() throws Exception {
        var session = session(user.getId());
        completed(session, Analysis.Status.PARTIAL);

        fetch(session.getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PARTIAL"));
    }

    @Test
    void noAnalysisYetIsResultNotReady() throws Exception {
        var session = session(user.getId());

        fetch(session.getId()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESULT_NOT_READY"));
    }

    @Test
    void queuedOrProcessingOrCanceledIsResultNotReady() throws Exception {
        var session = session(user.getId());
        analyses.saveAndFlush(Analysis.queued(user.getId(), session.getId(), OffsetDateTime.now()));

        for (String state : new String[]{"QUEUED", "PROCESSING", "CANCELED"}) {
            jdbc.update("update analyses set status = ?", state);
            fetch(session.getId()).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("RESULT_NOT_READY"));
        }
    }

    @Test
    void failedAnalysisIsAnalysisFailed() throws Exception {
        var session = session(user.getId());
        var analysis = analyses.saveAndFlush(Analysis.queued(user.getId(), session.getId(), OffsetDateTime.now()));
        analysis.fail("MODEL_ERROR", OffsetDateTime.now());
        analyses.saveAndFlush(analysis);

        fetch(session.getId()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ANALYSIS_FAILED"));
    }

    @Test
    void otherUsersOrUnknownOrDeletedSessionIs404() throws Exception {
        var other = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "다른회원"));
        var othersSession = session(other.getId());
        completed(othersSession, Analysis.Status.COMPLETED);
        var mine = session(user.getId());
        completed(mine, Analysis.Status.COMPLETED);
        jdbc.update("update coaching_sessions set deleted_at = CURRENT_TIMESTAMP where id = ?", mine.getId());

        for (UUID id : new UUID[]{othersSession.getId(), UUID.randomUUID(), mine.getId()}) {
            fetch(id).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        }
    }

    @Test
    void malformedSessionIdIsValidationError() throws Exception {
        mvc.perform(get("/api/coaching-sessions/not-a-uuid/result").header("Authorization", bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void requiresBearerToken() throws Exception {
        var session = session(user.getId());

        mvc.perform(get("/api/coaching-sessions/" + session.getId() + "/result"))
                .andExpect(status().isUnauthorized());
    }
}
