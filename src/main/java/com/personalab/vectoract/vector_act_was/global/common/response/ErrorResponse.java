package com.personalab.vectoract.vector_act_was.global.common.response;

import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;

import java.util.List;
import java.util.UUID;

/**
 * API 명세의 공통 실패 응답 포맷.
 */
public record ErrorResponse(
        boolean success,
        Object data,
        String message,
        ErrorDetail error
) {

    /**
     * ErrorCode 기반 에러 응답 생성
     */
    public static ErrorResponse of(ErrorCode errorCode) {
        return of(errorCode, errorCode.getMessage(), List.of());
    }

    /**
     * ErrorCode + 커스텀 메시지 에러 응답 생성
     */
    public static ErrorResponse of(ErrorCode errorCode, String message) {
        return of(errorCode, message, List.of());
    }

    /**
     * ErrorCode + 필드 검증 에러 목록 에러 응답 생성
     */
    public static ErrorResponse of(ErrorCode errorCode, List<FieldError> details) {
        return of(errorCode, errorCode.getMessage(), details);
    }

    private static ErrorResponse of(ErrorCode errorCode, String message, List<?> details) {
        return new ErrorResponse(false, null, message, new ErrorDetail(
                errorCode.name(), details, UUID.randomUUID().toString(), false
        ));
    }

    public record ErrorDetail(String code, List<?> details, String requestId, boolean retryable) {
    }

    /**
     * 필드 검증 에러 상세 정보
     *
     * @param field   에러 발생 필드명
     * @param value   요청에 담긴 값
     * @param reason  에러 사유
     */
    public record FieldError(
            String field,
            String value,
            String reason
    ) {
    }
}
