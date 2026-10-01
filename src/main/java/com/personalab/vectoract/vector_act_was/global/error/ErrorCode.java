package com.personalab.vectoract.vector_act_was.global.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 비즈니스 로직에서 발생할 수 있는 커스텀 에러 코드 정의.
 * <p>
 * 각 도메인별로 구분된 에러 코드를 관리하며,
 * HTTP 상태 코드와 사용자 친화적 메시지를 함께 포함한다.
 * <p>
 * 네이밍 컨벤션: {도메인}_{에러_설명}
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "로그인 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),

    // ========== Common (공통) ==========
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
    TERMS_VERSION_INVALID(HttpStatus.BAD_REQUEST, "약관 또는 개인정보처리방침 버전이 유효하지 않습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    CSRF_INVALID(HttpStatus.FORBIDDEN, "CSRF 토큰이 없거나 유효하지 않습니다."),

    // ========== Auth (인증/인가) ==========
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다."),
    EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "만료된 토큰입니다."),

    // ========== Member (회원) ==========
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 회원입니다."),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),
    DUPLICATE_NICKNAME(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다."),

    // ========== Script (대본) ==========
    SCRIPT_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 대본입니다."),

    // ========== Coaching (코칭) ==========
    COACHING_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 코칭 세션입니다."),
    COACHING_SESSION_ALREADY_ENDED(HttpStatus.BAD_REQUEST, "이미 종료된 코칭 세션입니다.");

    private final HttpStatus status;
    private final String message;
}
