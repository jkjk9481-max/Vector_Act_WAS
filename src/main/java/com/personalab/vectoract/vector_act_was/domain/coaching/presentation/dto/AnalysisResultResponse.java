package com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * C10 분석 결과 응답입니다. 7개 항목 점수는 항상 모두 존재하고, 분석할 수 없는 점수는 null입니다.
 *
 * @param analysisId     결과 식별자
 * @param sessionId      소속 세션 ID(업로드 분석이면 null)
 * @param source         LIVE_CAPTURE / UPLOADED_VIDEO
 * @param status         COMPLETED / PARTIAL
 * @param schemaVersion  응답 스키마 버전
 * @param modelVersion   분석 모델별 버전을 조합한 문자열
 * @param scoringVersion 점수 집계 비교 기준 버전
 * @param durationMs     검증된 영상 길이
 * @param overallScore   유효한 핵심 점수 평균(0~100, 소수 1자리). 평균을 낼 점수가 없으면 null
 * @param scores         항목별 점수. 키: expression, voice, gaze, posture, emotion, scriptDelivery, situationFit
 * @param summary        종합 피드백
 * @param strengths      강점(최대 10개)
 * @param improvements   개선점(최대 10개)
 * @param segments       시간순 구간 피드백(최대 300개)
 * @param nextPractice   다음 연습 추천(최대 5개)
 * @param generatedAt    결과 생성 시각
 */
public record AnalysisResultResponse(UUID analysisId, UUID sessionId, String source, String status,
                                     String schemaVersion, String modelVersion, String scoringVersion,
                                     Integer durationMs, BigDecimal overallScore, Map<String, Score> scores,
                                     String summary, List<String> strengths, List<String> improvements,
                                     List<Segment> segments, List<String> nextPractice,
                                     OffsetDateTime generatedAt) {

    /**
     * 항목 하나의 점수입니다.
     *
     * @param status     AVAILABLE / UNAVAILABLE / NOT_SUPPORTED
     * @param score      0~100, 소수 1자리. 분석할 수 없으면 null
     * @param confidence 0~1. 모델이 제공하지 않으면 null
     * @param reasonCode 정상이면 null, 불가·미지원이면 사유 코드
     */
    public record Score(String status, BigDecimal score, BigDecimal confidence, String reasonCode) {}

    /**
     * 구간 피드백입니다.
     *
     * @param feedbackId 피드백 ID
     * @param startMs    원본 영상 기준 시작 시각(ms)
     * @param endMs      종료 시각(ms)
     * @param category   EXPRESSION / VOICE / GAZE / POSTURE / EMOTION / SCRIPT_DELIVERY / SITUATION_FIT
     * @param kind       STRENGTH / IMPROVEMENT
     * @param severity   INFO / WARNING
     * @param message    피드백 본문
     * @param suggestion 구체적 연습 제안(없으면 null)
     */
    public record Segment(UUID feedbackId, int startMs, int endMs, String category, String kind, String severity,
                          String message, String suggestion) {}
}
