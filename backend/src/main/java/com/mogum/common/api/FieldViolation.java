package com.mogum.common.api;

public record FieldViolation(String field, Reason reason) {
    /** 요청 칸의 검증 실패를 공통 오류 응답에 싣는 까닭. */
    public enum Reason {
        /** @Size의 최댓값보다 문자열 길이 · 컬렉션이나 맵 크기 · 배열 길이가 클 때 쓴다. */
        TOO_LONG,
        /** @NotBlank 또는 @NotNull 제약을 위반한 요청 칸에 쓴다. */
        REQUIRED
    }
}
