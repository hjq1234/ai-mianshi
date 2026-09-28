package com.ke.nhservice.aimianshi.common.dto;

/**
 * 统一响应体。code == 0 表示成功，非 0 表示业务失败。
 * 业务失败用 HTTP 200 + code 传递，只有 401 会同时把 HTTP 状态设成 401，
 * 方便前端统一拦截跳登录页。
 */
public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    public static <T> ApiResponse<T> ok() {
        return new ApiResponse<>(0, "ok", null);
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}