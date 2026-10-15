package com.personalab.vectoract.vector_act_was.domain.dashboard.presentation.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * D05 주간 분석 / D06 월간 분석 응답입니다(두 API의 응답 필드가 같아 DTO를 공유합니다).
 *
 * @param periodStart          KST 기준 기간 시작일(주간: 월요일, 월간: 1일)
 * @param periodEnd            KST 기준 기간 종료일(포함)
 * @param practiceCount        확정 영상이 있던 비취소·비삭제 세션 수
 * @param analyzedSessionCount 유효 분석 세션 수(분석 상태 COMPLETED/PARTIAL)
 * @param metrics              7개 항목(expression/voice/gaze/posture/emotion/scriptDelivery/situationFit)
 * @param summary              AI 서버가 비동기 생성한 기간 피드백. 생성 전이면 null
 * @param summaryStatus        PENDING/READY/FAILED/INSUFFICIENT_DATA
 * @param statisticsUpdatedAt  기간 피드백 생성에 사용한 통계 기준 시각
 */
public record DashboardPeriodResponse(LocalDate periodStart, LocalDate periodEnd, int practiceCount,
                                      int analyzedSessionCount, List<Metric> metrics, String summary,
                                      String summaryStatus, OffsetDateTime statisticsUpdatedAt) {

    /**
     * @param category         expression/voice/gaze/posture/emotion/scriptDelivery/situationFit
     * @param average          이번 기간 유효 점수 산술평균(소수 1자리). 표본이 없으면 null
     * @param sampleCount      이번 기간 유효 표본 수
     * @param previousAverage  비교 가능한 이전 기간 평균. 비교 불가면 null
     * @param delta            average - previousAverage. 비교 불가면 null
     * @param comparisonStatus AVAILABLE/NO_PREVIOUS_DATA/INSUFFICIENT_DATA/VERSION_MISMATCH
     */
    public record Metric(String category, BigDecimal average, int sampleCount, BigDecimal previousAverage,
                         BigDecimal delta, String comparisonStatus) {}
}
