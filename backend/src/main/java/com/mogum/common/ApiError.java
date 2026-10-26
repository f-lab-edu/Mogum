package com.mogum.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

public record ApiError(String code, String message,
                       @JsonInclude(JsonInclude.Include.NON_NULL) Integer retryAfterSeconds,
                       @JsonInclude(JsonInclude.Include.NON_NULL) List<FieldViolation> fields) {
}
