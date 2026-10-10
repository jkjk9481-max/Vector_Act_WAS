package com.personalab.vectoract.vector_act_was.domain.script.presentation.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * S01·S02 응답입니다. content는 완료 전 null, failureCode는 정상 시 null이며 필드는 항상 포함됩니다.
 * expiresAt은 접수 24시간 뒤 작업과 결과가 삭제되는 시각입니다.
 */
public record ScriptExtractionResponse(UUID jobId, String status, String content,
                                       String failureCode, OffsetDateTime expiresAt) {
}
