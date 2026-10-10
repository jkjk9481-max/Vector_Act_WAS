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

    RESET_TOKEN_INVALID(HttpStatus.BAD_REQUEST, "유효하지 않은 비밀번호 재설정 토큰입니다."),
    EMAIL_CHANGE_TOKEN_INVALID(HttpStatus.BAD_REQUEST, "유효하지 않은 이메일 변경 토큰입니다."),

    REAUTH_REQUIRED(HttpStatus.FORBIDDEN, "유효한 재인증 토큰이 필요합니다."),
    ACCOUNT_DELETED(HttpStatus.CONFLICT, "이미 탈퇴한 계정입니다."),
    PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, "새 비밀번호와 확인 값이 일치하지 않습니다."),
    CURRENT_PASSWORD_INVALID(HttpStatus.FORBIDDEN, "현재 비밀번호가 올바르지 않습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),
    DEPENDENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "일시적으로 서비스를 이용할 수 없습니다."),

    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),

    // ========== Common (공통) ==========
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
    TERMS_VERSION_INVALID(HttpStatus.BAD_REQUEST, "약관 또는 개인정보처리방침 버전이 유효하지 않습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    CSRF_INVALID(HttpStatus.FORBIDDEN, "CSRF 토큰이 없거나 유효하지 않습니다."),
    FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "파일 크기가 허용 범위를 초과했습니다."),
    RESULT_EXPIRED(HttpStatus.GONE, "만료되어 삭제된 결과입니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 파일 형식입니다."),

    // ========== Auth (인증/인가) ==========
    AUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "Access Token 인증이 필요합니다."),
    ACCESS_EXPIRED(HttpStatus.UNAUTHORIZED, "만료된 Access Token입니다."),
    ACCESS_INVALID(HttpStatus.UNAUTHORIZED, "유효하지 않은 Access Token입니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    REFRESH_INVALID(HttpStatus.UNAUTHORIZED, "유효하지 않은 Refresh Token입니다."),
    REFRESH_EXPIRED(HttpStatus.UNAUTHORIZED, "만료된 Refresh Token입니다."),
    REFRESH_REUSED(HttpStatus.UNAUTHORIZED, "이미 사용된 Refresh Token입니다."),
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
