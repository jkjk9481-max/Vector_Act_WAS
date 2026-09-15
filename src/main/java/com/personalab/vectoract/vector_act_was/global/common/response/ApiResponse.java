package com.personalab.vectoract.vector_act_was.global.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * API 정상 응답을 위한 통합 응답 포맷.
 * <p>
 * 모든 성공 응답은 이 구조를 따르며, 클라이언트는 일관된 JSON 형태로 응답을 처리할 수 있다.
 *
 * @param status    HTTP 상태 코드
 * @param message   응답 메시지
 * @param data      응답 데이터
 * @param timestamp 응답 생성 시각
 * @param <T>       응답 데이터 타입
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        int status,
        String message,
        T data,
        LocalDateTime timestamp
) {

    /**
     * 데이터 포함 성공 응답 (200 OK)
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, "OK", data, LocalDateTime.now());
    }

    /**
     * 커스텀 메시지 포함 성공 응답 (200 OK)
     */
    public static <T> ApiResponse<T> ok(T data, String message) {
        return new ApiResponse<>(200, message, data, LocalDateTime.now());
    }

    /**
     * 데이터 없는 성공 응답 (200 OK)
     */
    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(200, "OK", null, LocalDateTime.now());
    }

    /**
     * 리소스 생성 성공 응답 (201 Created)
     */
    public static <T> ApiResponse<T> created(T data) {
        return new ApiResponse<>(201, "Created", data, LocalDateTime.now());
    }

    /**
     * 커스텀 상태코드 + 메시지 응답
     */
    public static <T> ApiResponse<T> of(int status, String message, T data) {
        return new ApiResponse<>(status, message, data, LocalDateTime.now());
    }
}
