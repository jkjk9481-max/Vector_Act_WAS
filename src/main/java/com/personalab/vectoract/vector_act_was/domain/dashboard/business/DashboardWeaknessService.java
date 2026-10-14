package com.personalab.vectoract.vector_act_was.domain.dashboard.business;

import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.Analysis;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.AnalysisFeedback;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.AnalysisFeedbackRepository;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.AnalysisRepository;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.CoachingSession;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.CoachingSessionRepository;
import com.personalab.vectoract.vector_act_was.domain.dashboard.presentation.dto.DashboardWeaknessResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * D07 누적 취약점. {@code analysis_feedbacks.kind = IMPROVEMENT}를 category·subcategory별로 세션 단위
 * 집계합니다(DB 설계서 7장). 기간 제한 없이 전체 기록을 누적하며, "개선 정도"만 KST 기준 최근 30일과
 * 그 직전 30일을 비교합니다(API 명세서 D07).
 *
 * <p>{@code suggestion}은 AI 서버가 주는 것이 아니라(연동이 없음) 저장된 피드백 중 가장 최근 문구를 쓰고,
 * 하나도 없으면 팀이 작성한 기본 문구를 씁니다(명세의 "응답 불가 시 최근 문구 또는 기본 문구").
 * 기본 문구는 {@code docs/VectorAct_코칭문구_최종.xlsx}의 "결과·대시보드 문구" 시트, 용도 = 누적 취약점
 * 개선 방향(AI 서버 DB coaching_phrases의 usage=WEAKNESS_DIRECTION에 대응) 버전 1에서 가져왔습니다.
 * 이 문구들은 AI 서버 전용 DB({@code vectoract_ai.coaching_phrases}, DB 설계서 11장)에 적재될 원본이며,
 * 이 코드는 AI 서버 연동이 없는 지금 단계의 임시 기본값으로만 둔 복사본입니다.
 */
@Service
public class DashboardWeaknessService {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int WINDOW_DAYS = 30;
    private static final int MIN_COMPARISON_SESSIONS = 3;
    private static final Predicate<OffsetDateTime> ALWAYS = createdAt -> true;

    /** API 응답의 category 키와 DB category의 대응입니다. 항상 이 순서입니다(D05·C10과 같은 목록). */
    private static final Map<String, String> CATEGORY_KEYS = categoryKeys();

    /** DB category별 세부 항목(subcategory) 코드 대응입니다. posture는 1차 범위 미지원이라 세부 항목이 없습니다. */
    private static final Map<String, Map<String, String>> SUBCATEGORY_KEYS = subcategoryKeys();

    /**
     * category별 기본 제안 문구입니다. voice/emotion처럼 세부 항목이 여러 개인 category는 그중 첫 번째
     * 세부 항목(voice→speechRate, emotion→emotionExpression)의 문구를 대표로 씁니다(명세에 category 대표
     * 문구 선정 기준이 없어 정한 값 — docs/D07-accumulated-weaknesses.md 참고). posture는 세부 항목이 없어
     * 코칭문구 시트에도 해당 문구가 없으므로 일반 문구를 둡니다.
     */
    private static final Map<String, String> DEFAULT_SUGGESTIONS = defaultSuggestions();

    private final CoachingSessionRepository sessions;
    private final AnalysisRepository analyses;
    private final AnalysisFeedbackRepository feedbacks;

    public DashboardWeaknessService(CoachingSessionRepository sessions, AnalysisRepository analyses,
                                    AnalysisFeedbackRepository feedbacks) {
        this.sessions = sessions;
        this.analyses = analyses;
        this.feedbacks = feedbacks;
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

    private static Map<String, Map<String, String>> subcategoryKeys() {
        var byCategory = new LinkedHashMap<String, Map<String, String>>();
        byCategory.put("EXPRESSION", Map.of("facialExpression", "FACIAL_EXPRESSION"));
        byCategory.put("GAZE", Map.of("gazeHold", "GAZE_HOLD"));
        var voice = new LinkedHashMap<String, String>();
        voice.put("speechRate", "SPEECH_RATE");
        voice.put("volume", "VOLUME");
        voice.put("intonation", "INTONATION");
        byCategory.put("VOICE", voice);
        var emotion = new LinkedHashMap<String, String>();
        emotion.put("emotionExpression", "EMOTION_EXPRESSION");
        emotion.put("emotionTransition", "EMOTION_TRANSITION");
        byCategory.put("EMOTION", emotion);
        byCategory.put("SCRIPT_DELIVERY", Map.of("scriptAccuracy", "SCRIPT_ACCURACY"));
        byCategory.put("SITUATION_FIT", Map.of("situationFit", "SITUATION_FIT"));
        byCategory.put("POSTURE", Map.of());
        return byCategory;
    }

    private static Map<String, String> defaultSuggestions() {
        var map = new LinkedHashMap<String, String>();
        map.put("EXPRESSION", "여러 연습에서 표정 변화가 적다는 피드백이 반복됐어요. 카메라 연기에서는 얼굴이 "
                + "감정의 대부분을 전달해요. 연습을 시작할 때마다 감정이 바뀌는 단어를 먼저 표시하고, 그 지점에서 "
                + "표정을 바꾸는 습관을 들여보세요.");
        map.put("GAZE", "시선이 자주 흔들리는 경향이 있어요. 시선이 흔들리면 인물이 자신 없어 보이거나 대사를 "
                + "떠올리는 것처럼 보일 수 있어요. 연습마다 상대가 있는 위치를 먼저 정하고 시작하는 습관을 들여보세요.");
        // voice 대표 문구(세부 항목 speechRate): 말 속도
        map.put("VOICE", "말 속도가 반복해서 지적됐어요. 속도가 흔들리면 대사의 뜻이 흐려지고 감정도 급하게 "
                + "보여요. 연습 전에 대본에 쉬는 곳을 표시하는 습관만 들여도 속도가 눈에 띄게 안정돼요.");
        // emotion 대표 문구(세부 항목 emotionExpression): 감정 표현
        map.put("EMOTION", "감정 표현이 반복해서 약하게 나타났어요. 감정을 직접 만들려 하기보다, 인물이 "
                + "상대에게서 원하는 것을 정하고 연기하면 감정이 자연스럽게 따라와요. 연기 전에 목표를 한 문장으로 "
                + "적는 습관을 들여보세요.");
        map.put("SCRIPT_DELIVERY", "대본과 다르게 말하는 부분이 반복됐어요. 대사가 정확해야 감정 연기에 온전히 "
                + "집중할 수 있어요. 연기 전에 대본을 소리 내어 두세 번 읽는 습관을 들여보세요.");
        map.put("SITUATION_FIT", "상황에 비해 감정의 크기가 맞지 않는 경우가 반복됐어요. 같은 감정도 장소와 "
                + "상대에 따라 크기가 달라져요. 연습 전에 장면의 상황을 한 문장으로 정리하고 시작해보세요.");
        // posture는 1차 범위 미지원이라 코칭문구 시트에 없어 일반 문구를 둡니다(실제로는 피드백이 생기지 않아
        // 이 경로를 타지 않습니다).
        map.put("POSTURE", "꾸준한 연습을 통해 이 항목을 개선해 보세요.");
        return map;
    }

    @Transactional(readOnly = true)
    public DashboardWeaknessResponse getWeaknesses(UUID userId) {
        OffsetDateTime now = OffsetDateTime.now(KST);
        var allSessions = sessions.findAllForDashboard(userId, CoachingSession.Status.CANCELED);
        if (allSessions.isEmpty()) return new DashboardWeaknessResponse(List.of(), now);

        var createdAtBySession = new HashMap<UUID, OffsetDateTime>();
        allSessions.forEach(s -> createdAtBySession.put(s.getId(), s.getCreatedAt()));

        var sessionIds = allSessions.stream().map(CoachingSession::getId).toList();
        var validAnalyses = analyses.findBySessionIdIn(sessionIds).stream()
                .filter(a -> a.getStatus() == Analysis.Status.COMPLETED || a.getStatus() == Analysis.Status.PARTIAL)
                .toList();
        if (validAnalyses.isEmpty()) return new DashboardWeaknessResponse(List.of(), now);

        // KST 기준 조회일을 포함한 최근 30일과 그 직전 30일(명세서 D07). 세션 createdAt(UTC 저장)을 KST 날짜
        // 경계의 OffsetDateTime으로 바꿔 비교합니다(D05·D06과 같은 방식).
        LocalDate today = LocalDate.now(KST);
        OffsetDateTime recentFrom = today.minusDays(WINDOW_DAYS - 1L).atStartOfDay(KST).toOffsetDateTime();
        OffsetDateTime recentTo = today.plusDays(1).atStartOfDay(KST).toOffsetDateTime();
        OffsetDateTime previousFrom = recentFrom.minusDays(WINDOW_DAYS);
        Predicate<OffsetDateTime> inRecentWindow = c -> !c.isBefore(recentFrom) && c.isBefore(recentTo);
        Predicate<OffsetDateTime> inPreviousWindow = c -> !c.isBefore(previousFrom) && c.isBefore(recentFrom);

        var analysisIds = validAnalyses.stream().map(Analysis::getId).toList();
        var improvementFeedback = feedbacks.findByAnalysisIdIn(analysisIds).stream()
                .filter(f -> "IMPROVEMENT".equals(f.getKind()))
                .toList();

        // analysisId -> 해당 분석에서 IMPROVEMENT가 1개 이상 있던 "category" / "category|subcategory" 집합
        var categoryHits = new HashMap<UUID, Set<String>>();
        var subcategoryHits = new HashMap<UUID, Set<String>>();
        // category -> 가장 최근 피드백(suggestion 선정용)
        var latestFeedbackByCategory = new HashMap<String, AnalysisFeedback>();
        for (AnalysisFeedback f : improvementFeedback) {
            categoryHits.computeIfAbsent(f.getAnalysisId(), k -> new HashSet<>()).add(f.getCategory());
            subcategoryHits.computeIfAbsent(f.getAnalysisId(), k -> new HashSet<>())
                    .add(f.getCategory() + "|" + f.getSubcategory());
            if (f.getSuggestion() != null) {
                latestFeedbackByCategory.merge(f.getCategory(), f,
                        (a, b) -> a.getCreatedAt().isAfter(b.getCreatedAt()) ? a : b);
            }
        }

        var items = CATEGORY_KEYS.entrySet().stream()
                .map(e -> buildItem(e.getKey(), e.getValue(), validAnalyses, createdAtBySession, categoryHits,
                        subcategoryHits, latestFeedbackByCategory, inRecentWindow, inPreviousWindow))
                .toList();
        return new DashboardWeaknessResponse(items, now);
    }

    private DashboardWeaknessResponse.Item buildItem(String key, String category, List<Analysis> validAnalyses,
            Map<UUID, OffsetDateTime> createdAtBySession, Map<UUID, Set<String>> categoryHits,
            Map<UUID, Set<String>> subcategoryHits, Map<String, AnalysisFeedback> latestFeedbackByCategory,
            Predicate<OffsetDateTime> inRecentWindow, Predicate<OffsetDateTime> inPreviousWindow) {
        Predicate<Analysis> hit = a -> categoryHits.getOrDefault(a.getId(), Set.of()).contains(category);
        var cumulative = count(validAnalyses, createdAtBySession, ALWAYS, hit);
        var recent = count(validAnalyses, createdAtBySession, inRecentWindow, hit);
        var previous = count(validAnalyses, createdAtBySession, inPreviousWindow, hit);
        var comparison = compare(recent, previous);

        var feedback = latestFeedbackByCategory.get(category);
        String suggestion = feedback != null ? feedback.getSuggestion() : DEFAULT_SUGGESTIONS.get(category);

        var subItems = SUBCATEGORY_KEYS.getOrDefault(category, Map.of()).entrySet().stream()
                .map(e -> buildSubItem(e.getKey(), category + "|" + e.getValue(), validAnalyses, createdAtBySession,
                        subcategoryHits, inRecentWindow, inPreviousWindow))
                .toList();

        return new DashboardWeaknessResponse.Item(key, cumulative.affected(), cumulative.eligible(),
                frequency(cumulative), comparison.improvement(), comparison.status(), suggestion, subItems);
    }

    private DashboardWeaknessResponse.SubItem buildSubItem(String key, String categorySubcategory,
            List<Analysis> validAnalyses, Map<UUID, OffsetDateTime> createdAtBySession,
            Map<UUID, Set<String>> subcategoryHits, Predicate<OffsetDateTime> inRecentWindow,
            Predicate<OffsetDateTime> inPreviousWindow) {
        Predicate<Analysis> hit = a -> subcategoryHits.getOrDefault(a.getId(), Set.of()).contains(categorySubcategory);
        var cumulative = count(validAnalyses, createdAtBySession, ALWAYS, hit);
        var recent = count(validAnalyses, createdAtBySession, inRecentWindow, hit);
        var previous = count(validAnalyses, createdAtBySession, inPreviousWindow, hit);
        var comparison = compare(recent, previous);

        return new DashboardWeaknessResponse.SubItem(key, cumulative.affected(), cumulative.eligible(),
                frequency(cumulative), comparison.improvement(), comparison.status());
    }

    private static Counts count(List<Analysis> validAnalyses, Map<UUID, OffsetDateTime> createdAtBySession,
            Predicate<OffsetDateTime> inWindow, Predicate<Analysis> hit) {
        int eligible = 0;
        int affected = 0;
        for (Analysis a : validAnalyses) {
            var createdAt = createdAtBySession.get(a.getSessionId());
            if (createdAt == null || !inWindow.test(createdAt)) continue;
            eligible++;
            if (hit.test(a)) affected++;
        }
        return new Counts(affected, eligible);
    }

    private static Comparison compare(Counts recent, Counts previous) {
        // D05·D06(DashboardPeriodService)과 같은 우선순위입니다: 최근 30일 쪽 표본이 3개 미만이면(0 포함)
        // 비교 자체가 불가능하므로 INSUFFICIENT_DATA가 우선하고, 최근 표본이 충분한데 직전 30일만 0건일
        // 때만 NO_PREVIOUS_DATA로 구분합니다.
        if (previous.eligible() == 0 && recent.eligible() >= MIN_COMPARISON_SESSIONS) {
            return new Comparison(null, "NO_PREVIOUS_DATA");
        }
        if (previous.eligible() < MIN_COMPARISON_SESSIONS || recent.eligible() < MIN_COMPARISON_SESSIONS) {
            return new Comparison(null, "INSUFFICIENT_DATA");
        }
        BigDecimal recentFrequency = frequency(recent);
        BigDecimal previousFrequency = frequency(previous);
        BigDecimal improvement = previousFrequency.subtract(recentFrequency).setScale(2, RoundingMode.HALF_UP);
        return new Comparison(improvement, "AVAILABLE");
    }

    private static BigDecimal frequency(Counts counts) {
        if (counts.eligible() == 0) return null;
        return BigDecimal.valueOf(counts.affected())
                .divide(BigDecimal.valueOf(counts.eligible()), 2, RoundingMode.HALF_UP);
    }

    private record Counts(int affected, int eligible) {}

    private record Comparison(BigDecimal improvement, String status) {}
}
