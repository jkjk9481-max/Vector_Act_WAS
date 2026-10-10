package com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto;

import java.util.List;

/**
 * C06 응답입니다. 세션의 청크 현황을 한 번에 돌려줍니다(최대 121개, 페이지 없음).
 *
 * @param items               청크 번호 순 목록
 * @param missingChunkIndexes 선언된 범위(0 ~ 가장 큰 청크 번호) 안에서 검증(VERIFIED)이 끝나지 않은 번호
 */
public record ChunkStatusResponse(List<Item> items, List<Integer> missingChunkIndexes) {
    /**
     * @param chunkIndex 청크 번호
     * @param status     RESERVED(업로드 대기) / VERIFIED(검증 완료)
     * @param startMs    원본 영상 기준 시작 시각(ms)
     * @param endMs      원본 영상 기준 종료 시각(ms)
     * @param sizeBytes  선언한 크기
     * @param sha256     선언한 SHA-256
     */
    public record Item(int chunkIndex, String status, int startMs, int endMs, long sizeBytes, String sha256) {}
}
