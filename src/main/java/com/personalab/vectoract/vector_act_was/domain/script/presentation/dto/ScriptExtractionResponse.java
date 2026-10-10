package com.personalab.vectoract.vector_act_was.domain.script.presentation.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * S01·S02 응답입니다. content는 완료 전 null, failureCode는 정상 시 null이며 필드는 항상 포함됩니다.
 * expiresAt은 접수 24시간 뒤 작업과 결과가 삭제되는 시각입니다.
 *
 * @param jobId       OCR 작업 ID. S02 조회 경로의 {@code extractionId}로 사용합니다
 * @param status      QUEUED / PROCESSING / COMPLETED / FAILED
 * @param content     COMPLETED일 때만 채워지는 추출 본문
 * @param failureCode FAILED일 때만 채워지는 실패 코드(예: OCR_NO_TEXT)
 * @param expiresAt   작업과 결과가 삭제되는 시각. 이 시각 이후 조회는 410 RESULT_EXPIRED
 */
public record ScriptExtractionResponse(UUID jobId, String status, String content,
                                       String failureCode, OffsetDateTime expiresAt) {
}
