package com.personalab.vectoract.vector_act_was.global.error.exception;

import com.personalab.vectoract.vector_act_was.global.error.ErrorCode;
import lombok.Getter;

/**
 * 비즈니스 로직 예외의 기본 클래스.
 * <p>
 * 모든 도메인별 커스텀 예외는 이 클래스를 상속하며,
 * {@link ErrorCode}를 통해 일관된 에러 정보를 전달한다.
 * <p>
 * GlobalExceptionHandler에서 이 예외를 캐치하여 {@code ErrorResponse}로 변환한다.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
