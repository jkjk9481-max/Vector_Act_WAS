package com.personalab.vectoract.vector_act_was.domain.coaching.business;

import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.Analysis;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.AnalysisFeedbackRepository;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.AnalysisRepository;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.AnalysisScore;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.AnalysisScoreRepository;
import com.personalab.vectoract.vector_act_was.domain.coaching.persistence.CoachingSessionRepository;
import com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto.AnalysisResultResponse;
import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import com.personalab.vectoract.vector_act_was.global.error.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * C10 분석 결과 조회. 완료(COMPLETED) 또는 부분 완료(PARTIAL)된 분석만 결과로 돌려줍니다.
 * 결과는 AI 서버 연동 단계가 analyses·analysis_scores·analysis_feedbacks에 저장한 값을 읽어 응답 형식으로 바꿉니다.
 */
@Service
public class AnalysisResultService {
    /** API 응답의 점수 항목 키와 DB category의 대응입니다. 응답에는 이 7개가 항상 이 순서로 존재합니다. */
    private static final Map<String, String> SCORE_KEYS = scoreKeys();

    private final CoachingSessionRepository sessions;
    private final AnalysisRepository analyses;
    private final AnalysisScoreRepository scores;
    private final AnalysisFeedbackRepository feedbacks;
    private final ObjectMapper objectMapper;

    public AnalysisResultService(CoachingSessionRepository sessions, AnalysisRepository analyses,
                                 AnalysisScoreRepository scores, AnalysisFeedbackRepository feedbacks,
                                 ObjectMapper objectMapper) {
        this.sessions = sessions;
        this.analyses = analyses;
        this.scores = scores;
        this.feedbacks = feedbacks;
        this.objectMapper = objectMapper;
    }

    private static Map<String, String> scoreKeys() {
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

    /**
     * 오류: 타인·삭제된·없는 세션은 404, 결과가 아직 없거나 진행 중이거나 취소됐으면 409 RESULT_NOT_READY,
     * 분석이 실패했으면 409 ANALYSIS_FAILED.
     */
    @Transactional(readOnly = true)
    public AnalysisResultResponse get(UUID userId, UUID sessionId) {
        sessions.findByIdAndUserIdAndDeletedAtIsNull(sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        var analysis = analyses.findBySessionId(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESULT_NOT_READY));
        switch (analysis.getStatus()) {
            case COMPLETED, PARTIAL -> { }
            case FAILED -> throw new BusinessException(ErrorCode.ANALYSIS_FAILED);
            default -> throw new BusinessException(ErrorCode.RESULT_NOT_READY);
        }

        var response = new AnalysisResultResponse(analysis.getId(), analysis.getSessionId(), analysis.getSource(),
                analysis.getStatus().name(), analysis.getSchemaVersion(), analysis.getModelVersion(),
                analysis.getScoringVersion(), analysis.getDurationMs(), oneDecimal(analysis.getOverallScore()),
                scoreItems(analysis), analysis.getSummary(), strings(analysis.getStrengths()),
                strings(analysis.getImprovements()), segments(analysis), strings(analysis.getNextPractice()),
                analysis.getGeneratedAt());
        return response;
    }

    /**
     * 7개 항목 점수를 만듭니다. DB에 행이 없는 항목은 분석 불가(UNAVAILABLE)로,
     * 1차 범위에서 지원하지 않는 POSTURE는 NOT_SUPPORTED로 채워 "7개 항목은 항상 존재"를 지킵니다.
     */
    private Map<String, AnalysisResultResponse.Score> scoreItems(Analysis analysis) {
        var byCategory = new HashMap<String, AnalysisScore>();
        for (AnalysisScore row : scores.findByAnalysisId(analysis.getId())) byCategory.put(row.getCategory(), row);

        var items = new LinkedHashMap<String, AnalysisResultResponse.Score>();
        SCORE_KEYS.forEach((key, category) -> {
            var row = byCategory.get(category);
            if (row == null) {
                var status = category.equals("POSTURE") ? AnalysisScore.Status.NOT_SUPPORTED
                        : AnalysisScore.Status.UNAVAILABLE;
                items.put(key, new AnalysisResultResponse.Score(status.name(), null, null, null));
            } else {
                items.put(key, new AnalysisResultResponse.Score(row.getStatus().name(), oneDecimal(row.getScore()),
                        row.getConfidence(), row.getReasonCode()));
            }
        });
        return items;
    }

    private List<AnalysisResultResponse.Segment> segments(Analysis analysis) {
        return feedbacks.findTop300ByAnalysisIdOrderByStartMsAscCreatedAtAsc(analysis.getId()).stream()
                .map(f -> new AnalysisResultResponse.Segment(f.getId(), f.getStartMs(), f.getEndMs(),
                        f.getCategory(), f.getKind(), f.getSeverity(), f.getMessage(), f.getSuggestion()))
                .toList();
    }

    // 점수는 소수 1자리로 반올림해 응답합니다(DB는 소수 2자리까지 저장).
    private static BigDecimal oneDecimal(BigDecimal value) {
        return value == null ? null : value.setScale(1, RoundingMode.HALF_UP);
    }

    // JSON 문자열 배열 컬럼을 목록으로 읽습니다. 값이 없으면 빈 목록입니다.
    private List<String> strings(String json) {
        if (json == null || json.isBlank()) return List.of();
        return objectMapper.readValue(json, new TypeReference<List<String>>() { });
    }
}
