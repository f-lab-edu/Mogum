package com.mogum.common;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/** MVC와 보안 필터가 공유하는 오류 본문 · 상태 · 헤더 변환. */
public final class ApiErrorResponses {
    private ApiErrorResponses() {
    }

    public static ResponseEntity<ApiError> of(ErrorCode code, Integer retryAfterSeconds) {
        return create(code.status().value(), HttpHeaders.EMPTY, code, retryAfterSeconds);
    }

    public static ResponseEntity<ApiError> forStatus(int status, HttpHeaders headers) {
        var code = switch (status) {
            case 404 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            default -> status >= 500 ? ErrorCode.INTERNAL_ERROR : ErrorCode.INVALID_REQUEST;
        };
        return create(status, headers, code, null);
    }

    public static void write(HttpServletResponse response, ObjectMapper mapper,
                             ErrorCode code, Integer retryAfterSeconds) throws IOException {
        var error = of(code, retryAfterSeconds);
        response.setStatus(error.getStatusCode().value());
        error.getHeaders().forEach((name, values) -> {
            response.setHeader(name, values.getFirst());
            values.stream().skip(1).forEach(value -> response.addHeader(name, value));
        });
        mapper.writeValue(response.getOutputStream(), error.getBody());
    }

    private static ResponseEntity<ApiError> create(int status, HttpHeaders headers,
                                                    ErrorCode code, Integer retryAfterSeconds) {
        if (retryAfterSeconds != null && retryAfterSeconds <= 0) {
            throw new IllegalArgumentException("retryAfterSeconds must be positive");
        }
        var response = ResponseEntity.status(status).headers(headers).contentType(MediaType.APPLICATION_JSON);
        if (retryAfterSeconds != null) {
            response.header(HttpHeaders.RETRY_AFTER, retryAfterSeconds.toString());
        }
        return response.body(new ApiError(code.name(), code.message(), retryAfterSeconds));
    }
}
