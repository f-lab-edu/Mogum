package com.mogum.course.dto;

import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.CodePointLength;

public record CourseTitleRequest(@NotBlank @CodePointLength(max = 100, message = "field.TOO_LONG") String title) { }
