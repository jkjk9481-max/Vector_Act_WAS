package com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * D01 연습 기록 목록 응답입니다.
 *
 * @param items         조건에 맞는 본인 기록(취소·삭제된 세션, 업로드 분석은 제외)
 * @param page          요청한 페이지(0부터)
 * @param size          요청한 페이지 크기
 * @param totalElements 총 개수
 * @param totalPages    ceil(totalElements/size), 0건이면 0
 * @param hasNext       다음 페이지 존재 여부
 */
public record CoachingSessionListResponse(List<Item> items, int page, int size, long totalElements,
                                          int totalPages, boolean hasNext) {

    /**
     * @param sessionId      세션 ID
     * @param createdAt      생성 시각
     * @param durationMs     확정 영상 길이. 아직 검증되지 않았으면 null
     * @param status         세션 상태
     * @param videoStatus    영상 상태
     * @param analysisStatus 분석 상태
     * @param overallScore   분석 점수(소수 1자리). 분석이 아직 없거나 완료 전이면 null
     */
    public record Item(UUID sessionId, OffsetDateTime createdAt, Integer durationMs, String status,
                       String videoStatus, String analysisStatus, BigDecimal overallScore) {}
}
