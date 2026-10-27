package com.mogum.common;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> apiException(ApiException exception) {
        return ApiErrorResponses.of(exception.errorCode(), exception.retryAfterSeconds());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var errors = exception.getBindingResult().getAllErrors();
        var violations = errors.stream().map(ApiExceptionHandler::fieldViolation).toList();
        var response = !violations.isEmpty() && violations.stream().allMatch(violation -> violation != null)
                ? ApiErrorResponses.of(ErrorCode.FIELD_INVALID, null, violations)
                : ApiErrorResponses.of(ErrorCode.INVALID_REQUEST, null);
        return new ResponseEntity<>(response.getBody(), response.getHeaders(), response.getStatusCode());
    }

    private static FieldViolation fieldViolation(ObjectError error) {
        if (!(error instanceof FieldError fieldError) || !error.contains(ConstraintViolation.class)) {
            return null;
        }
        var violation = error.unwrap(ConstraintViolation.class);
        var descriptor = violation.getConstraintDescriptor();
        var annotationType = descriptor.getAnnotation().annotationType();
        if (annotationType == NotBlank.class || annotationType == NotNull.class) {
            return new FieldViolation(fieldError.getField(), FieldViolation.Reason.REQUIRED);
        }
        if (annotationType == Size.class && descriptor.getAttributes().get("max") instanceof Integer max
                && sizeOf(violation.getInvalidValue()) > max) {
            return new FieldViolation(fieldError.getField(), FieldViolation.Reason.TOO_LONG);
        }
        return null;
    }

    private static int sizeOf(Object value) {
        if (value instanceof CharSequence text) return text.length();
        if (value instanceof Collection<?> collection) return collection.size();
        if (value instanceof Map<?, ?> map) return map.size();
        if (value != null && value.getClass().isArray()) return Array.getLength(value);
        return -1;
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers,
                                                         HttpStatusCode status, WebRequest request) {
        var response = ApiErrorResponses.forStatus(status.value(), headers);
        return new ResponseEntity<>(response.getBody(), response.getHeaders(), response.getStatusCode());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception exception) {
        if (exception instanceof ErrorResponse error) {
            return ApiErrorResponses.forStatus(error.getStatusCode().value(), error.getHeaders());
        }
        logger.error("Unhandled API exception", exception);
        return ApiErrorResponses.of(ErrorCode.INTERNAL_ERROR, null);
    }
}
