package com.mogum.common;

public record FieldViolation(String field, Reason reason) {
    public enum Reason { TOO_LONG, REQUIRED }
}
