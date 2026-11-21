package com.mogum.common.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

public record ApiError(String code, String message,
	@JsonInclude(JsonInclude.Include.NON_NULL) Integer retryAfterSeconds,
	@JsonInclude(JsonInclude.Include.NON_NULL) List<FieldViolation> fields) {
}
