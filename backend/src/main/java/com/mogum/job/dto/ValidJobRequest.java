package com.mogum.job.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = JobReceiveRequestValidator.class)
public @interface ValidJobRequest {
    String message() default "field.INVALID_VALUE";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
