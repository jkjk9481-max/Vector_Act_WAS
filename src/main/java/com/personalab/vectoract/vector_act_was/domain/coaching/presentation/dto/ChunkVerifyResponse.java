package com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto;

/**
 * C05 응답입니다.
 *
 * @param chunkIndex 검증한 청크 번호
 * @param status     항상 {@code VERIFIED}(성공 응답은 검증이 끝난 경우뿐입니다)
 * @param duplicate  이미 검증이 끝난 청크를 다시 확인했으면 true
 */
public record ChunkVerifyResponse(int chunkIndex, String status, boolean duplicate) {}
