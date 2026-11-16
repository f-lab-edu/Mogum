package com.mogum.common.api;

import java.util.Objects;

public final class ApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final ErrorCode errorCode;
    private final Integer retryAfterSeconds;
    private final String responseMessage;

    public ApiException(ErrorCode errorCode) {
        this(errorCode, null);
    }

    public ApiException(ErrorCode errorCode, Integer retryAfterSeconds) {
        this(errorCode, retryAfterSeconds, errorCode.message());
    }

    private ApiException(ErrorCode errorCode, Integer retryAfterSeconds, String responseMessage) {
        super(Objects.requireNonNull(errorCode).name());
        if (retryAfterSeconds != null && retryAfterSeconds <= 0) {
            throw new IllegalArgumentException("retryAfterSeconds must be positive");
        }
        this.errorCode = errorCode;
        this.retryAfterSeconds = retryAfterSeconds;
        this.responseMessage = Objects.requireNonNull(responseMessage);
    }

    /** 사용자 입력을 붙이지 않고 서버가 정한 안내 문구만 넘긴다. 예외 메시지에는 code만 남긴다. */
    public static ApiException withMessage(ErrorCode errorCode, String responseMessage) {
        return new ApiException(errorCode, null, responseMessage);
    }

    public String responseMessage() {
        return responseMessage;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
