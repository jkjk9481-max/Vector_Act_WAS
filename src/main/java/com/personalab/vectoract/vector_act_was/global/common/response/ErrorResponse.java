package com.personalab.vectoract.vector_act_was.global.common.response;

import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * API 에러 응답을 위한 통합 에러 포맷.
 * <p>
 * 모든 예외 상황에서 클라이언트는 이 구조로 에러 정보를 전달받는다.
 *
 * @param status    HTTP 상태 코드
 * @param code      커스텀 에러 코드 (ErrorCode Enum의 name)
 * @param message   에러 메시지
 * @param errors    필드 검증 에러 목록 (Validation 실패 시)
 * @param timestamp 에러 발생 시각
 */
public record ErrorResponse(
        int status,
        String code,
        String message,
        List<FieldError> errors,
        LocalDateTime timestamp
) {

    /**
     * ErrorCode 기반 에러 응답 생성
     */
    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(
                errorCode.getStatus().value(),
                errorCode.name(),
                errorCode.getMessage(),
                List.of(),
                LocalDateTime.now()
        );
    }

    /**
     * ErrorCode + 커스텀 메시지 에러 응답 생성
     */
    public static ErrorResponse of(ErrorCode errorCode, String message) {
        return new ErrorResponse(
                errorCode.getStatus().value(),
                errorCode.name(),
                message,
                List.of(),
                LocalDateTime.now()
        );
    }

    /**
     * ErrorCode + 필드 검증 에러 목록 에러 응답 생성
     */
    public static ErrorResponse of(ErrorCode errorCode, List<FieldError> errors) {
        return new ErrorResponse(
                errorCode.getStatus().value(),
                errorCode.name(),
                errorCode.getMessage(),
                errors,
                LocalDateTime.now()
        );
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
