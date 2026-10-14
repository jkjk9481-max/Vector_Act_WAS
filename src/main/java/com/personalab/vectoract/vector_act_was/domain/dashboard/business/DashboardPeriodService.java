package com.personalab.vectoract.vector_act_was.domain.dashboard.business;

import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.Analysis;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.AnalysisRepository;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.AnalysisScore;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.AnalysisScoreRepository;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.CoachingSession;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.CoachingSessionRepository;
import com.personalab.vectoract.vector_act_was.domain.dashboard.persistence.DashboardSummary;
import com.personalab.vectoract.vector_act_was.domain.dashboard.persistence.DashboardSummaryRepository;
import com.personalab.vectoract.vector_act_was.domain.dashboard.presentation.dto.DashboardPeriodResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * D05 주간 분석 · D06 월간 분석. 본인의 실시간 코칭 세션만 집계 대상이며(업로드 영상 분석은 제외), 취소·삭제된
 * 세션도 제외합니다(API 명세서 D05·D06, DB 설계서 7장 대시보드 집계 기준). 두 API는 기간 경계 계산만 다르고
 * 집계·비교 로직이 같아 이 서비스를 공유합니다.
 *
 * <p>기간 피드백({@code summary}/{@code summaryStatus})은 {@code dashboard_summaries}를 읽기만 합니다.
 * 그 행을 만들고 AI 서버에 생성을 요청하는 단계(세션 분석 확정·기록 삭제 트리거)는 아직 구현되지 않아
 * (AI 서버 연동 자체가 이 코드베이스에 없음) 캐시 행이 없으면 유효 분석 세션 유무로 기본값을 돌려줍니다.
 */
@Service
public class DashboardPeriodService {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int MIN_COMPARISON_SAMPLES = 3;

    /** API 응답의 항목 키와 DB category의 대응입니다. 응답에는 이 7개가 항상 이 순서로 존재합니다(C10과 같은 목록). */
    private static final Map<String, String> CATEGORY_KEYS = categoryKeys();

    private final CoachingSessionRepository sessions;
    private final AnalysisRepository analyses;
    private final AnalysisScoreRepository scores;
    private final DashboardSummaryRepository summaries;

    public DashboardPeriodService(CoachingSessionRepository sessions, AnalysisRepository analyses,
                                  AnalysisScoreRepository scores, DashboardSummaryRepository summaries) {
        this.sessions = sessions;
        this.analyses = analyses;
        this.scores = scores;
        this.summaries = summaries;
    }

    private static Map<String, String> categoryKeys() {
        var keys = new LinkedHashMap<String, String>();
        keys.put("expression", "EXPRESSION");
        keys.put("voice", "VOICE");
        keys.put("gaze", "GAZE");
        keys.put("posture", "POSTURE");
        keys.put("emotion", "EMOTION");
        keys.put("scriptDelivery", "SCRIPT_DELIVERY");
        keys.put("situationFit", "SITUATION_FIT");
        return keys;
    }

    /** date가 없으면 KST 기준 오늘을 쓰며, 그 날짜가 속한 KST 월요일~일요일이 조회 대상 주입니다. */
    @Transactional(readOnly = true)
    public DashboardPeriodResponse getWeekly(UUID userId, LocalDate date) {
        LocalDate base = date != null ? date : LocalDate.now(KST);
        LocalDate periodStart = base.with(DayOfWeek.MONDAY);
        LocalDate periodEnd = periodStart.plusDays(6);
        LocalDate previousStart = periodStart.minusWeeks(1);
        LocalDate previousEnd = periodStart.minusDays(1);
        return build(userId, DashboardSummary.PeriodType.WEEKLY, periodStart, periodEnd, previousStart, previousEnd);
    }

    /** month가 없으면 KST 기준 이번 달을 쓰며, 그 달의 1일~말일이 조회 대상 기간입니다. */
    @Transactional(readOnly = true)
    public DashboardPeriodResponse getMonthly(UUID userId, YearMonth month) {
        YearMonth base = month != null ? month : YearMonth.now(KST);
        LocalDate periodStart = base.atDay(1);
        LocalDate periodEnd = base.atEndOfMonth();
        YearMonth previousMonth = base.minusMonths(1);
        LocalDate previousStart = previousMonth.atDay(1);
        LocalDate previousEnd = previousMonth.atEndOfMonth();
        return build(userId, DashboardSummary.PeriodType.MONTHLY, periodStart, periodEnd, previousStart, previousEnd);
    }

    private DashboardPeriodResponse build(UUID userId, DashboardSummary.PeriodType periodType, LocalDate periodStart,
            LocalDate periodEnd, LocalDate previousStart, LocalDate previousEnd) {
        var current = loadPeriod(userId, periodStart, periodEnd);
        var previous = loadPeriod(userId, previousStart, previousEnd);

        var metrics = CATEGORY_KEYS.entrySet().stream()
                .map(e -> buildMetric(e.getKey(), e.getValue(), current, previous))
                .toList();

        var cached = summaries.findByUserIdAndPeriodTypeAndPeriodStart(userId, periodType, periodStart);
        String summaryText = cached.map(DashboardSummary::getSummary).orElse(null);
        String summaryStatus = cached.map(s -> s.getStatus().name())
                .orElse(current.analyzedSessionCount() == 0 ? "INSUFFICIENT_DATA" : "PENDING");
        OffsetDateTime statisticsUpdatedAt = cached.map(DashboardSummary::getStatisticsUpdatedAt)
                .orElseGet(() -> OffsetDateTime.now(KST));

        return new DashboardPeriodResponse(periodStart, periodEnd, current.practiceCount(),
                current.analyzedSessionCount(), metrics, summaryText, summaryStatus, statisticsUpdatedAt);
    }

    private PeriodAggregate loadPeriod(UUID userId, LocalDate start, LocalDate end) {
        OffsetDateTime from = start.atStartOfDay(KST).toOffsetDateTime();
        OffsetDateTime to = end.plusDays(1).atStartOfDay(KST).toOffsetDateTime();
        var periodSessions = sessions.findForDashboard(userId, from, to, CoachingSession.Status.CANCELED);
        int practiceCount = (int) periodSessions.stream().filter(DashboardPeriodService::hadConfirmedVideo).count();

        var sessionIds = periodSessions.stream().map(CoachingSession::getId).toList();
        var validAnalyses = (sessionIds.isEmpty() ? List.<Analysis>of() : analyses.findBySessionIdIn(sessionIds))
                .stream()
                .filter(a -> a.getStatus() == Analysis.Status.COMPLETED || a.getStatus() == Analysis.Status.PARTIAL)
                .toList();

        var analysisIds = validAnalyses.stream().map(Analysis::getId).toList();
        var scoreRows = analysisIds.isEmpty() ? List.<AnalysisScore>of() : scores.findByAnalysisIdIn(analysisIds);
        // Collectors.toMap은 value가 null이면 NPE를 던지므로(scoringVersion은 nullable) 직접 채웁니다.
        var versionByAnalysis = new HashMap<UUID, String>();
        validAnalyses.forEach(a -> versionByAnalysis.put(a.getId(), a.getScoringVersion()));

        Map<String, List<AnalysisScore>> byCategory = scoreRows.stream()
                .filter(s -> s.getStatus() == AnalysisScore.Status.AVAILABLE)
                .collect(Collectors.groupingBy(AnalysisScore::getCategory));

        return new PeriodAggregate(practiceCount, validAnalyses.size(), byCategory, versionByAnalysis);
    }

    /** READY(현재 재생 가능)뿐 아니라 EXPIRED/DELETED(한때 확정됐다가 이후 삭제·만료)도 "확정 영상이 있던" 세션입니다(D03과 같은 구분). */
    private static boolean hadConfirmedVideo(CoachingSession session) {
        return switch (session.getVideoStatus()) {
            case READY, EXPIRED, DELETED -> true;
            default -> false;
        };
    }

    private DashboardPeriodResponse.Metric buildMetric(String key, String category, PeriodAggregate current,
            PeriodAggregate previous) {
        var currentScores = current.scoresByCategory().getOrDefault(category, List.of());
        var previousScores = previous.scoresByCategory().getOrDefault(category, List.of());
        BigDecimal currentAverage = average(currentScores);
        int currentSampleCount = currentScores.size();

        // 이번 기간 표본이 3개 미만이면(0 포함) 비교 자체가 불가능하므로 INSUFFICIENT_DATA가 우선합니다.
        // 이전 기간이 비어 있어도 이번 기간 표본이 충분할 때만 "비교 대상이 없다"는 NO_PREVIOUS_DATA로 구분합니다
        // (docs/VectorAct_messages_json_확정.zip의 SUMMARY_REQUEST 예시: posture는 양쪽 다 0개라 INSUFFICIENT_DATA).
        if (previousScores.isEmpty() && currentSampleCount >= MIN_COMPARISON_SAMPLES) {
            return new DashboardPeriodResponse.Metric(key, currentAverage, currentSampleCount, null, null,
                    "NO_PREVIOUS_DATA");
        }
        if (currentSampleCount < MIN_COMPARISON_SAMPLES || previousScores.size() < MIN_COMPARISON_SAMPLES) {
            return new DashboardPeriodResponse.Metric(key, currentAverage, currentSampleCount, null, null,
                    "INSUFFICIENT_DATA");
        }
        Set<String> currentVersions = versionsOf(currentScores, current.scoringVersionByAnalysis());
        Set<String> previousVersions = versionsOf(previousScores, previous.scoringVersionByAnalysis());
        if (!currentVersions.equals(previousVersions)) {
            return new DashboardPeriodResponse.Metric(key, currentAverage, currentSampleCount, null, null,
                    "VERSION_MISMATCH");
        }

        BigDecimal previousAverage = average(previousScores);
        BigDecimal delta = currentAverage.subtract(previousAverage).setScale(1, RoundingMode.HALF_UP);
        return new DashboardPeriodResponse.Metric(key, currentAverage, currentSampleCount, previousAverage, delta,
                "AVAILABLE");
    }

    private static Set<String> versionsOf(List<AnalysisScore> categoryScores, Map<UUID, String> versionByAnalysis) {
        return categoryScores.stream().map(s -> versionByAnalysis.get(s.getAnalysisId()))
                .filter(Objects::nonNull).collect(Collectors.toSet());
    }

    // 점수는 소수 1자리로 반올림합니다(C10·D01과 같은 규칙).
    private static BigDecimal average(List<AnalysisScore> categoryScores) {
        if (categoryScores.isEmpty()) return null;
        BigDecimal sum = categoryScores.stream().map(AnalysisScore::getScore).reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(categoryScores.size()), 1, RoundingMode.HALF_UP);
    }

    private record PeriodAggregate(int practiceCount, int analyzedSessionCount,
            Map<String, List<AnalysisScore>> scoresByCategory, Map<UUID, String> scoringVersionByAnalysis) {}
}
