package com.personalab.vectoract.vector_act_was.global.common.response;

public record ApiResponse<T>(boolean success, T data, String message, Object error) {

    /**
     * 데이터 포함 성공 응답 (200 OK)
     */
    public static <T> ApiResponse<T> ok(T data) {
        return ok(data, "OK");
    }

    /**
     * 커스텀 메시지 포함 성공 응답 (200 OK)
     */
    public static <T> ApiResponse<T> ok(T data, String message) {
        return new ApiResponse<>(true, data, message, null);
    }

    /**
     * 데이터 없는 성공 응답 (200 OK)
     */
    public static ApiResponse<Void> ok() {
        return ok(null, "OK");
    }

    /**
     * 리소스 생성 성공 응답 (201 Created)
     */
    public static <T> ApiResponse<T> created(T data) {
        return ok(data, "Created");
    }
}
