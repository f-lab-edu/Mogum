package com.mogum.common;

import com.fasterxml.jackson.annotation.JsonInclude;

public record ApiError(String code, String message,
                       @JsonInclude(JsonInclude.Include.NON_NULL) Integer retryAfterSeconds) {
}
