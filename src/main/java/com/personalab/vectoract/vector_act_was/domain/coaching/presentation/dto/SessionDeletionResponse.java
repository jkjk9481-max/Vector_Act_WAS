package com.personalab.vectoract.vector_act_was.domain.coaching.presentation.dto;

import java.time.OffsetDateTime;

/**
 * D04 연습 기록 삭제 응답입니다.
 *
 * @param deletionStatus PENDING(파일·자식 데이터 정리 중) 또는 DONE
 * @param deletedAt      삭제 접수 시각. 즉시 조회·집계에서 제외됩니다
 */
public record SessionDeletionResponse(String deletionStatus, OffsetDateTime deletedAt) {}
