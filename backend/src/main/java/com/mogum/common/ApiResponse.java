package com.mogum.common;

import java.util.Objects;

/** 본문이 있는 성공 응답. data에는 응답 DTO record를, 목록은 DTO의 이름 있는 필드에 둔다. */
public record ApiResponse<T>(T data) {
    public ApiResponse {
        Objects.requireNonNull(data, "data");
    }

    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(data);
    }
}
