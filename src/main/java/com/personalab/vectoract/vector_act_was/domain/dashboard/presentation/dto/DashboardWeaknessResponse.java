package com.personalab.vectoract.vector_act_was.domain.dashboard.presentation.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * D07 누적 취약점 응답입니다.
 *
 * @param items     카테고리별 집계. 분석 가능 세션이 전혀 없으면 빈 배열, 그 외에는 7개 항목이 항상 존재
 * @param updatedAt 집계 시각(캐시 없이 조회 시점에 계산)
 */
public record DashboardWeaknessResponse(List<Item> items, OffsetDateTime updatedAt) {

    /**
     * @param category             expression/voice/gaze/posture/emotion/scriptDelivery/situationFit
     * @param affectedSessionCount 개선 피드백이 1개 이상 있는 세션 수(누적, 전체 기간)
     * @param eligibleSessionCount 분석 가능 세션 수(누적, 전체 기간)
     * @param frequency            affected/eligible. 분모 0이면 null
     * @param improvement          직전 30일 frequency - 최근 30일 frequency. 비교 불가면 null
     * @param improvementStatus    AVAILABLE/NO_PREVIOUS_DATA/INSUFFICIENT_DATA
     * @param suggestion           개선 방향(최대 500자). 최근 피드백 문구 또는 기본 문구
     * @param subItems             세부 항목별 집계. posture는 세부 항목이 없어 항상 빈 배열
     */
    public record Item(String category, int affectedSessionCount, int eligibleSessionCount, BigDecimal frequency,
                       BigDecimal improvement, String improvementStatus, String suggestion,
                       List<SubItem> subItems) {}

    /**
     * 필드 의미는 {@link Item}과 같으며, category 대신 세부 항목 코드(facialExpression/gazeHold/speechRate/
     * volume/intonation/emotionExpression/emotionTransition/scriptAccuracy/situationFit) 기준으로 집계합니다.
     */
    public record SubItem(String subcategory, int affectedSessionCount, int eligibleSessionCount,
                          BigDecimal frequency, BigDecimal improvement, String improvementStatus) {}
}
