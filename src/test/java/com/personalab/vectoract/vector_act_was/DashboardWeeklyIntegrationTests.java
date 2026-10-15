package com.personalab.vectoract.vector_act_was;

import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.*;
import com.personalab.vectoract.vector_act_was.domain.dashboard.persistence.DashboardSummary;
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

/** D05 주간 분석 통합 테스트. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dashboardweekly;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "auth.withdrawal.purge-enabled=false",
        "script.ocr.purge-enabled=false"})
@AutoConfigureMockMvc
class DashboardWeeklyIntegrationTests {
    // 2026-01-05(월)~2026-01-11(일)이 조회 대상 주, 그 앞 2025-12-29(월)~2026-01-04(일)이 이전 주입니다.
    private static final OffsetDateTime CURRENT_WEEK = OffsetDateTime.parse("2026-01-06T03:00:00+09:00");
    private static final OffsetDateTime PREVIOUS_WEEK = OffsetDateTime.parse("2025-12-30T03:00:00+09:00");

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
        return mvc.perform(get("/api/dashboard/weekly" + query).header("Authorization", bearer));
    }

    @Test
    void emptyWeekReturnsZerosAndSevenMetricsWithInsufficientData() throws Exception {
        // 이번 주 표본이 0개면(이전 주도 0개) INSUFFICIENT_DATA입니다. NO_PREVIOUS_DATA는 이번 기간 표본이
        // 3개 이상인데 이전 기간만 비어 있을 때만 씁니다(아래 comparisonAvailable... 류 테스트가 그 경계를 덮습니다).
        fetch("?date=2026-01-07").andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.periodStart").value("2026-01-05"))
                .andExpect(jsonPath("$.data.periodEnd").value("2026-01-11"))
                .andExpect(jsonPath("$.data.practiceCount").value(0))
                .andExpect(jsonPath("$.data.analyzedSessionCount").value(0))
                .andExpect(jsonPath("$.data.metrics.length()").value(7))
                .andExpect(jsonPath("$.data.metrics[0].category").value("expression"))
                .andExpect(jsonPath("$.data.metrics[0].average").value((Object) null))
                .andExpect(jsonPath("$.data.metrics[0].sampleCount").value(0))
                .andExpect(jsonPath("$.data.metrics[0].comparisonStatus").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.data.metrics[3].category").value("posture"))
                .andExpect(jsonPath("$.data.metrics[6].category").value("situationFit"))
                .andExpect(jsonPath("$.data.summary").value((Object) null))
                .andExpect(jsonPath("$.data.summaryStatus").value("INSUFFICIENT_DATA"));
    }

    @Test
    void noPreviousDataOnlyWhenCurrentHasEnoughSamplesButPreviousIsEmpty() throws Exception {
        for (int i = 0; i < 3; i++) {
            var s = session(user.getId(), CURRENT_WEEK.plusHours(i));
            var a = completedAnalysis(s, "v1");
            score(a, "EXPRESSION", new BigDecimal("80.00"));
        }

        fetch("?date=2026-01-07").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics[0].comparisonStatus").value("NO_PREVIOUS_DATA"))
                .andExpect(jsonPath("$.data.metrics[0].previousAverage").value((Object) null))
                .andExpect(jsonPath("$.data.metrics[0].delta").value((Object) null));
    }

    @Test
    void practiceCountIncludesOnlySessionsThatHadConfirmedVideo() throws Exception {
        var ready = session(user.getId(), CURRENT_WEEK);
        setVideoStatus(ready, CoachingSession.VideoStatus.READY);
        var expired = session(user.getId(), CURRENT_WEEK);
        setVideoStatus(expired, CoachingSession.VideoStatus.EXPIRED);
        var deleted = session(user.getId(), CURRENT_WEEK);
        setVideoStatus(deleted, CoachingSession.VideoStatus.DELETED);
        var neverConfirmed = session(user.getId(), CURRENT_WEEK); // NOT_STARTED

        fetch("?date=2026-01-07").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.practiceCount").value(3));
    }

    @Test
    void excludesCanceledDeletedAndOtherUsersSessions() throws Exception {
        var canceled = session(user.getId(), CURRENT_WEEK);
        setVideoStatus(canceled, CoachingSession.VideoStatus.READY);
        jdbc.update("update coaching_sessions set status = 'CANCELED' where id = ?", canceled.getId());

        var deleted = session(user.getId(), CURRENT_WEEK);
        setVideoStatus(deleted, CoachingSession.VideoStatus.READY);
        jdbc.update("update coaching_sessions set deleted_at = CURRENT_TIMESTAMP where id = ?", deleted.getId());

        var other = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "다른회원"));
        var otherSession = session(other.getId(), CURRENT_WEEK);
        setVideoStatus(otherSession, CoachingSession.VideoStatus.READY);

        var mine = session(user.getId(), CURRENT_WEEK);
        setVideoStatus(mine, CoachingSession.VideoStatus.READY);

        fetch("?date=2026-01-07").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.practiceCount").value(1));
    }

    @Test
    void analyzedSessionCountCountsCompletedAndPartialOnly() throws Exception {
        var completed = session(user.getId(), CURRENT_WEEK);
        completedAnalysis(completed, "v1");

        var partialSession = session(user.getId(), CURRENT_WEEK);
        var partial = Analysis.queued(partialSession.getUserId(), partialSession.getId(), OffsetDateTime.now(ZoneOffset.UTC));
        partial.complete(Analysis.Status.PARTIAL, "1.0", "mp-1", "v1", 60000, new BigDecimal("70.00"), "요약",
                "[]", "[]", "[]", OffsetDateTime.now(ZoneOffset.UTC));
        analyses.saveAndFlush(partial);

        var queuedSession = session(user.getId(), CURRENT_WEEK); // 분석 QUEUED, 집계 제외

        fetch("?date=2026-01-07").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analyzedSessionCount").value(2));
    }

    @Test
    void metricAverageUsesOnlyAvailableScoresAndRoundsToOneDecimal() throws Exception {
        var s1 = session(user.getId(), CURRENT_WEEK);
        var a1 = completedAnalysis(s1, "v1");
        score(a1, "EXPRESSION", new BigDecimal("80.00"));
        score(a1, "VOICE", new BigDecimal("50.00")); // 다른 항목, expression 평균에는 영향 없음

        var s2 = session(user.getId(), CURRENT_WEEK);
        var a2 = completedAnalysis(s2, "v1");
        score(a2, "EXPRESSION", new BigDecimal("81.00"));

        var s3 = session(user.getId(), CURRENT_WEEK);
        var a3 = completedAnalysis(s3, "v1");
        scores.saveAndFlush(AnalysisScore.of(a3.getId(), "EXPRESSION", AnalysisScore.Status.UNAVAILABLE, null, null,
                "AUDIO_TOO_SHORT", OffsetDateTime.now(ZoneOffset.UTC))); // UNAVAILABLE은 평균에서 제외

        fetch("?date=2026-01-07").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics[0].category").value("expression"))
                .andExpect(jsonPath("$.data.metrics[0].sampleCount").value(2))
                .andExpect(jsonPath("$.data.metrics[0].average").value(80.5));
    }

    @Test
    void comparisonAvailableComputesDeltaAgainstPreviousWeek() throws Exception {
        for (int i = 0; i < 3; i++) {
            var s = session(user.getId(), PREVIOUS_WEEK.plusHours(i));
            var a = completedAnalysis(s, "v1");
            score(a, "EXPRESSION", new BigDecimal("70.00"));
        }
        for (int i = 0; i < 3; i++) {
            var s = session(user.getId(), CURRENT_WEEK.plusHours(i));
            var a = completedAnalysis(s, "v1");
            score(a, "EXPRESSION", new BigDecimal("80.00"));
        }

        fetch("?date=2026-01-07").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics[0].average").value(80.0))
                .andExpect(jsonPath("$.data.metrics[0].previousAverage").value(70.0))
                .andExpect(jsonPath("$.data.metrics[0].delta").value(10.0))
                .andExpect(jsonPath("$.data.metrics[0].comparisonStatus").value("AVAILABLE"));
    }

    @Test
    void comparisonInsufficientDataWhenEitherPeriodHasFewerThanThreeSamples() throws Exception {
        for (int i = 0; i < 3; i++) {
            var s = session(user.getId(), PREVIOUS_WEEK.plusHours(i));
            var a = completedAnalysis(s, "v1");
            score(a, "EXPRESSION", new BigDecimal("70.00"));
        }
        var s = session(user.getId(), CURRENT_WEEK);
        var a = completedAnalysis(s, "v1");
        score(a, "EXPRESSION", new BigDecimal("80.00")); // 이번 주 표본 1개 < 3

        fetch("?date=2026-01-07").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics[0].comparisonStatus").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.data.metrics[0].previousAverage").value((Object) null))
                .andExpect(jsonPath("$.data.metrics[0].delta").value((Object) null));
    }

    @Test
    void comparisonVersionMismatchWhenScoringVersionDiffers() throws Exception {
        for (int i = 0; i < 3; i++) {
            var s = session(user.getId(), PREVIOUS_WEEK.plusHours(i));
            var a = completedAnalysis(s, "v1");
            score(a, "EXPRESSION", new BigDecimal("70.00"));
        }
        for (int i = 0; i < 3; i++) {
            var s = session(user.getId(), CURRENT_WEEK.plusHours(i));
            var a = completedAnalysis(s, "v2");
            score(a, "EXPRESSION", new BigDecimal("80.00"));
        }

        fetch("?date=2026-01-07").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics[0].comparisonStatus").value("VERSION_MISMATCH"))
                .andExpect(jsonPath("$.data.metrics[0].previousAverage").value((Object) null))
                .andExpect(jsonPath("$.data.metrics[0].delta").value((Object) null));
    }

    @Test
    void dateOmittedDefaultsToCurrentWeek() throws Exception {
        fetch("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.periodStart").exists())
                .andExpect(jsonPath("$.data.periodEnd").exists());
    }

    @Test
    void mondayAndSundayBoundaryBelongToSameWeek() throws Exception {
        fetch("?date=2026-01-05").andExpect(status().isOk()) // 월요일
                .andExpect(jsonPath("$.data.periodStart").value("2026-01-05"))
                .andExpect(jsonPath("$.data.periodEnd").value("2026-01-11"));
        fetch("?date=2026-01-11").andExpect(status().isOk()) // 일요일
                .andExpect(jsonPath("$.data.periodStart").value("2026-01-05"))
                .andExpect(jsonPath("$.data.periodEnd").value("2026-01-11"));
    }

    @Test
    void invalidDateFormatIsValidationError() throws Exception {
        fetch("?date=2026/01/07").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void usesCachedDashboardSummaryWhenPresent() throws Exception {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("insert into dashboard_summaries (id, user_id, period_type, period_start, period_end, status, "
                        + "summary, statistics_updated_at, created_at, updated_at) "
                        + "values (?, ?, 'WEEKLY', ?, ?, 'READY', ?, ?, ?, ?)",
                UUID.randomUUID(), user.getId(), java.sql.Date.valueOf("2026-01-05"),
                java.sql.Date.valueOf("2026-01-11"), "이번 주는 발화 속도가 안정적이었습니다.", now, now, now);

        fetch("?date=2026-01-07").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary").value("이번 주는 발화 속도가 안정적이었습니다."))
                .andExpect(jsonPath("$.data.summaryStatus").value("READY"));
    }

    @Test
    void summaryStatusIsPendingWhenValidAnalysisExistsButNoCachedRow() throws Exception {
        var s = session(user.getId(), CURRENT_WEEK);
        completedAnalysis(s, "v1");

        fetch("?date=2026-01-07").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary").value((Object) null))
                .andExpect(jsonPath("$.data.summaryStatus").value("PENDING"));
    }

    @Test
    void requiresBearerToken() throws Exception {
        mvc.perform(get("/api/dashboard/weekly")).andExpect(status().isUnauthorized());
    }
}
