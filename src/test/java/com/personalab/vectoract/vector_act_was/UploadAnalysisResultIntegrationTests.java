package com.personalab.vectoract.vector_act_was;

import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.*;
import com.personalab.vectoract.vector_act_was.domain.member.persistence.*;
import com.personalab.vectoract.vector_act_was.global.auth.AccessTokenProvider;
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

/** U08 업로드 분석 결과 조회 통합 테스트. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:uploadanalysisresult;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "auth.withdrawal.purge-enabled=false",
        "script.ocr.purge-enabled=false"})
@AutoConfigureMockMvc
class UploadAnalysisResultIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired AuthOneTimeTokenRepository oneTimeTokens;
    @Autowired CoachingSessionRepository sessions;
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
        sessions.deleteAll();
        oneTimeTokens.deleteAll();
        refreshTokens.deleteAll();
        users.deleteAll();
        user = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "회원"));
        bearer = "Bearer " + accessTokens.issue(user.getId());
    }

    /** 결과가 저장된 완료 상태의 업로드 분석을 만듭니다(AnalysisResultIntegrationTests와 같은 데이터 모양). */
    private Analysis completed(UUID userId, Analysis.Status status) {
        var now = OffsetDateTime.now();
        var analysis = Analysis.queuedForUpload(userId, UUID.randomUUID(), now);
        analysis.complete(status, "1.0", "mp-1.lgbm-1", "score-1", 60000, new BigDecimal("78.46"),
                "전반적으로 안정적입니다.", "[\"시선이 안정적입니다\"]", "[\"말 속도를 줄여 보세요\"]",
                "[\"호흡 연습\",\"감정 전환 연습\"]", now);
        analysis = analyses.saveAndFlush(analysis);
        scores.saveAndFlush(AnalysisScore.of(analysis.getId(), "EXPRESSION", AnalysisScore.Status.AVAILABLE,
                new BigDecimal("82.35"), new BigDecimal("0.912"), null, now));
        return analysis;
    }

    private ResultActions fetch(UUID analysisId) throws Exception {
        return mvc.perform(get("/api/upload-analyses/" + analysisId + "/result").header("Authorization", bearer));
    }

    @Test
    void returnsCompletedResultWithNullSessionId() throws Exception {
        var analysis = completed(user.getId(), Analysis.Status.COMPLETED);

        fetch(analysis.getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisId").value(analysis.getId().toString()))
                .andExpect(jsonPath("$.data.sessionId").value((Object) null))
                .andExpect(jsonPath("$.data.source").value("UPLOADED_VIDEO"))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.scores.expression.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.scores.posture.status").value("NOT_SUPPORTED"))
                .andExpect(jsonPath("$.data.summary").value("전반적으로 안정적입니다."));
    }

    @Test
    void partialStatusIsAccepted() throws Exception {
        var analysis = completed(user.getId(), Analysis.Status.PARTIAL);
        fetch(analysis.getId()).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PARTIAL"));
    }

    @Test
    void failedAnalysisIsConflict() throws Exception {
        var analysis = analyses.saveAndFlush(Analysis.queuedForUpload(user.getId(), UUID.randomUUID(), OffsetDateTime.now()));
        jdbc.update("update analyses set status = 'FAILED', failure_code = 'ANALYSIS_TIMEOUT'");

        fetch(analysis.getId()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ANALYSIS_FAILED"));
    }

    @Test
    void queuedAnalysisIsResultNotReady() throws Exception {
        var analysis = analyses.saveAndFlush(Analysis.queuedForUpload(user.getId(), UUID.randomUUID(), OffsetDateTime.now()));

        fetch(analysis.getId()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESULT_NOT_READY"));
    }

    @Test
    void expiredResultIsGone() throws Exception {
        var analysis = completed(user.getId(), Analysis.Status.COMPLETED);
        jdbc.update("update analyses set expires_at = ?", OffsetDateTime.now().minusSeconds(1));

        fetch(analysis.getId()).andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("RESULT_EXPIRED"));
    }

    @Test
    void otherUsersOrUnknownOrLiveCaptureAnalysisIs404() throws Exception {
        var other = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "다른회원"));
        var othersAnalysis = completed(other.getId(), Analysis.Status.COMPLETED);
        fetch(othersAnalysis.getId()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        fetch(UUID.randomUUID()).andExpect(status().isNotFound());

        var session = sessions.saveAndFlush(CoachingSession.prepare(user.getId(), "대본", "상황", true, false, false,
                CoachingSession.Intensity.NORMAL, OffsetDateTime.now()));
        var liveAnalysis = analyses.saveAndFlush(Analysis.queued(user.getId(), session.getId(), OffsetDateTime.now()));
        fetch(liveAnalysis.getId()).andExpect(status().isNotFound());
    }

    @Test
    void requiresBearerToken() throws Exception {
        var analysis = completed(user.getId(), Analysis.Status.COMPLETED);
        mvc.perform(get("/api/upload-analyses/" + analysis.getId() + "/result")).andExpect(status().isUnauthorized());
    }
}
