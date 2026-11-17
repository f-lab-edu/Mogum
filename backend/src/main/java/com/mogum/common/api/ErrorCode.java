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

    /** 만들기를 받을 때 크레딧이 모자라면 409로 반환하며 문구에 다음에 채워지는 날을 넣는다. */
    CREDIT_INSUFFICIENT(HttpStatus.CONFLICT, "크레딧이 부족합니다"),

    /** 한 사람의 기다림과 만드는 중 작업이 상한에 닿으면 409로 반환한다. */
    TOO_MANY_IN_PROGRESS(HttpStatus.CONFLICT, "지금 만드는 것이 끝나면 다시 시도해 주세요"),

    /** 코스가 짜기 전이 아니거나 로드맵 상태가 맞지 않으면 강의 목록 받기를 409로 거절한다. */
    COURSE_PLAN_NOT_ALLOWED(HttpStatus.CONFLICT, "지금은 이 코스의 강의 목록을 만들 수 없어요"),

    /** 앞선 짜기 전 코스가 있거나 바로 앞 코스가 다 마침이 아니면 409로 반환한다. */
    PREVIOUS_COURSE_NOT_COMPLETED(HttpStatus.CONFLICT, "앞 코스를 다 마치면 만들 수 있어요"),

    /** 성공하지 않은 로드맵 다시 짜기가 있으면 첫 코스의 받기를 409로 거절한다. */
    ROADMAP_REPLAN_IN_PROGRESS(HttpStatus.CONFLICT, "다시 짜는 중에는 시작할 수 없어요"),

    /** 코스나 로드맵이 검토 중이 아니면 다시 짜기 받기를 409로 거절한다. */
    REPLAN_NOT_ALLOWED(HttpStatus.CONFLICT, "검토 중일 때만 다시 짤 수 있어요"),

    /** 입력이 너무 커 실패한 작업의 다시 시도를 거절할 때 409로 반환한다. */
    JOB_RETRY_NOT_ALLOWED(HttpStatus.CONFLICT, "주제를 좁혀 새로 만들어 주세요"),

    /** 실패가 아닌 작업의 버리기를 거절할 때 409로 반환한다. */
    JOB_DISCARD_NOT_ALLOWED(HttpStatus.CONFLICT, "실패한 만들기만 버릴 수 있습니다"),

    /** 작업 읽기에서 INPUT_TOO_LARGE 외의 실패를 다시 시도 · 버리기 묶음으로 반환한다. */
    JOB_TEMPORARY_FAILURE(HttpStatus.CONFLICT, "만들지 못했습니다. 다시 시도하거나 버려 주세요"),

    /** 작업 읽기에서 INPUT_TOO_LARGE를 버리기만 가능한 입력 문제 묶음으로 반환한다. */
    JOB_INPUT_FAILURE(HttpStatus.CONFLICT, "주제를 좁혀 새로 만들어 주세요"),

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
