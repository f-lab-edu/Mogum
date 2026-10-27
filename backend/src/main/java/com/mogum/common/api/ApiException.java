package com.mogum.common.api;

import java.util.Objects;

public final class ApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final ErrorCode errorCode;
    private final Integer retryAfterSeconds;

    public ApiException(ErrorCode errorCode) {
        this(errorCode, null);
    }

    public ApiException(ErrorCode errorCode, Integer retryAfterSeconds) {
        super(Objects.requireNonNull(errorCode).name());
        if (retryAfterSeconds != null && retryAfterSeconds <= 0) {
            throw new IllegalArgumentException("retryAfterSeconds must be positive");
        }
        this.errorCode = errorCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
