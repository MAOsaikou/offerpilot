package com.offerpilot.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        int code,
        String message,
        T data,
        String traceId,
        List<FieldError> errors
) {

    public static <T> ApiResponse<T> ok(T data, String traceId) {
        return new ApiResponse<>(0, "ok", data, traceId, null);
    }

    public static ApiResponse<Void> fail(int code, String message, String traceId) {
        return new ApiResponse<>(code, message, null, traceId, null);
    }

    public static ApiResponse<Void> fail(int code, String message, String traceId, List<FieldError> errors) {
        return new ApiResponse<>(code, message, null, traceId, errors);
    }

    public record FieldError(String field, String reason) {
    }
}
