package com.mogum.common.api;

public record FieldViolation(String field, Reason reason) {
    public enum Reason { TOO_LONG, REQUIRED }
}
