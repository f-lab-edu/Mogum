package com.mogum.api;

public record ApiError(String code, String message) {
    public static ApiError forStatus(int status) {
        return switch (status) {
            case 404 -> new ApiError("NOT_FOUND", "찾을 수 없습니다");
            case 405 -> new ApiError("METHOD_NOT_ALLOWED", "지원하지 않는 요청 방식입니다");
            default -> status >= 500
                    ? new ApiError("INTERNAL_ERROR", "요청을 처리할 수 없습니다")
                    : new ApiError("INVALID_REQUEST", "요청을 확인할 수 없습니다");
        };
    }
}
