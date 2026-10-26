package com.mogum.common;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

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
        boolean sizeOnly = !errors.isEmpty() && errors.stream()
                .allMatch(error -> error instanceof FieldError && "Size".equals(error.getCode()));
        var response = sizeOnly
                ? ApiErrorResponses.of(ErrorCode.STRUCTURAL_LIMIT, null,
                        exception.getBindingResult().getFieldErrors().stream().map(FieldError::getField).toList())
                : ApiErrorResponses.of(ErrorCode.INVALID_REQUEST, null);
        return new ResponseEntity<>(response.getBody(), response.getHeaders(), response.getStatusCode());
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
