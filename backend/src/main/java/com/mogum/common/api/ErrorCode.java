package com.mogum.common.api;

import org.springframework.http.HttpStatus;

/** MVC와 보안 필터가 공통 오류 응답에 싣는 오류 묶음. */
public enum ErrorCode {
    /** 인증이 없거나 세션 신원이 유효하지 않을 때, 신청 처리에서 회원 ID로 사용자를 찾지 못할 때 401로 반환한다. */
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다"),
    /** 신청 전 · 신청 · 거절됨인 사람이 허용된 계정 동작 밖의 API를 부르면 403으로 반환한다. */
    ACCOUNT_NOT_APPROVED(HttpStatus.FORBIDDEN, "승인된 계정이 필요합니다"),
    /** 보안 접근 거절 처리에서 CSRF 검증 실패로 분류되면 403으로 반환한다. */
    CSRF_INVALID(HttpStatus.FORBIDDEN, "요청을 확인할 수 없습니다"),
    /** 404 오류를 공통 변환하거나 인증된 사람의 API 밖 접근을 거절할 때 404로 반환한다. */
    NOT_FOUND(HttpStatus.NOT_FOUND, "찾을 수 없습니다"),
    /** 지원하지 않는 HTTP 메서드로 발생한 405 오류를 공통 응답으로 변환할 때 쓴다. */
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다"),
    /** 요청 검증 실패를 칸별로 분류할 수 없거나 404 · 405 외의 클라이언트 오류를 공통 변환할 때 쓴다. */
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청을 확인할 수 없습니다"),
    /** 요청 검증 실패를 모두 지원하는 칸별 위반으로 분류할 수 있을 때 400으로 반환한다. */
    FIELD_INVALID(HttpStatus.BAD_REQUEST, "입력을 확인해 주십시오"),
    /** 처리되지 않은 예외는 500으로 반환하고, 500 이상 오류 상태를 공통 변환할 때도 쓴다. */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "요청을 처리할 수 없습니다");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }
}
