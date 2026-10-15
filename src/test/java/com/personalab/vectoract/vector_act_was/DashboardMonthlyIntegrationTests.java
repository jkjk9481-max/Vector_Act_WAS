package com.personalab.vectoract.vector_act_was;

import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.*;
import com.personalab.vectoract.vector_act_was.domain.dashboard.persistence.DashboardSummaryRepository;
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
import java.time.ZoneOffset;
import java.util.UUID;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** D06 월간 분석 통합 테스트. 집계 로직은 D05와 공유하므로 기간 계산·캐시 반영 위주로 검증합니다. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dashboardmonthly;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "auth.withdrawal.purge-enabled=false",
        "script.ocr.purge-enabled=false"})
@AutoConfigureMockMvc
class DashboardMonthlyIntegrationTests {
    private static final OffsetDateTime CURRENT_MONTH = OffsetDateTime.parse("2026-01-15T03:00:00+09:00");
    private static final OffsetDateTime PREVIOUS_MONTH = OffsetDateTime.parse("2025-12-15T03:00:00+09:00");

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired AuthOneTimeTokenRepository oneTimeTokens;
    @Autowired CoachingSessionRepository sessions;
    @Autowired SessionVideoRepository videos;
    @Autowired SessionChunkRepository chunks;
    @Autowired AnalysisRepository analyses;
    @Autowired AnalysisScoreRepository scores;
    @Autowired DashboardSummaryRepository summaries;
    @Autowired IdempotencyKeyRepository idempotencyKeys;
    @Autowired AccessTokenProvider accessTokens;
    @Autowired JdbcTemplate jdbc;
    private User user;
    private String bearer;

    @BeforeEach
    void prepare() {
        summaries.deleteAll();
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

    private CoachingSession session(UUID userId, OffsetDateTime createdAt) {
        return sessions.saveAndFlush(CoachingSession.prepare(userId, "대본", "상황", true, false, false,
                CoachingSession.Intensity.NORMAL, createdAt));
    }

    private void setVideoStatus(CoachingSession session, CoachingSession.VideoStatus status) {
        jdbc.update("update coaching_sessions set video_status = ? where id = ?", status.name(), session.getId());
    }

    private Analysis completedAnalysis(CoachingSession session, String scoringVersion) {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var analysis = Analysis.queued(session.getUserId(), session.getId(), now);
        analysis.complete(Analysis.Status.COMPLETED, "1.0", "mp-1.lgbm-1", scoringVersion, 60000,
                new BigDecimal("80.00"), "요약", "[]", "[]", "[]", now);
        return analyses.saveAndFlush(analysis);
    }

    private void score(Analysis analysis, String category, BigDecimal value) {
        scores.saveAndFlush(AnalysisScore.of(analysis.getId(), category, AnalysisScore.Status.AVAILABLE, value,
                new BigDecimal("0.900"), null, OffsetDateTime.now(ZoneOffset.UTC)));
    }

    private ResultActions fetch(String query) throws Exception {
        return mvc.perform(get("/api/dashboard/monthly" + query).header("Authorization", bearer));
    }

    @Test
    void monthBoundaryCoversFirstToLastDay() throws Exception {
        fetch("?month=2026-01").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.periodStart").value("2026-01-01"))
                .andExpect(jsonPath("$.data.periodEnd").value("2026-01-31"))
                .andExpect(jsonPath("$.data.metrics.length()").value(7));
    }

    @Test
    void februaryUsesCorrectLastDay() throws Exception {
        fetch("?month=2026-02").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.periodEnd").value("2026-02-28"));
    }

    @Test
    void practiceCountAndAnalyzedSessionCountWithinMonth() throws Exception {
        var ready = session(user.getId(), CURRENT_MONTH);
        setVideoStatus(ready, CoachingSession.VideoStatus.READY);
        completedAnalysis(ready, "v1");
        var outsideMonth = session(user.getId(), PREVIOUS_MONTH);
        setVideoStatus(outsideMonth, CoachingSession.VideoStatus.READY);

        fetch("?month=2026-01").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.practiceCount").value(1))
                .andExpect(jsonPath("$.data.analyzedSessionCount").value(1));
    }

    @Test
    void comparisonAvailableAgainstPreviousCalendarMonth() throws Exception {
        for (int i = 0; i < 3; i++) {
            var s = session(user.getId(), PREVIOUS_MONTH.plusDays(i));
            var a = completedAnalysis(s, "v1");
            score(a, "EXPRESSION", new BigDecimal("70.00"));
        }
        for (int i = 0; i < 3; i++) {
            var s = session(user.getId(), CURRENT_MONTH.plusDays(i));
            var a = completedAnalysis(s, "v1");
            score(a, "EXPRESSION", new BigDecimal("80.00"));
        }

        fetch("?month=2026-01").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics[0].average").value(80.0))
                .andExpect(jsonPath("$.data.metrics[0].previousAverage").value(70.0))
                .andExpect(jsonPath("$.data.metrics[0].delta").value(10.0))
                .andExpect(jsonPath("$.data.metrics[0].comparisonStatus").value("AVAILABLE"));
    }

    @Test
    void monthOmittedDefaultsToCurrentMonth() throws Exception {
        fetch("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.periodStart").exists());
    }

    @Test
    void invalidMonthFormatIsValidationError() throws Exception {
        fetch("?month=2026-13").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        fetch("?month=2026/01").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void usesCachedDashboardSummaryWhenPresent() throws Exception {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("insert into dashboard_summaries (id, user_id, period_type, period_start, period_end, status, "
                        + "summary, statistics_updated_at, created_at, updated_at) "
                        + "values (?, ?, 'MONTHLY', ?, ?, 'READY', ?, ?, ?, ?)",
                UUID.randomUUID(), user.getId(), java.sql.Date.valueOf("2026-01-01"),
                java.sql.Date.valueOf("2026-01-31"), "이번 달은 안정적이었습니다.", now, now, now);

        fetch("?month=2026-01").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary").value("이번 달은 안정적이었습니다."))
                .andExpect(jsonPath("$.data.summaryStatus").value("READY"));
    }

    @Test
    void requiresBearerToken() throws Exception {
        mvc.perform(get("/api/dashboard/monthly")).andExpect(status().isUnauthorized());
    }
}
