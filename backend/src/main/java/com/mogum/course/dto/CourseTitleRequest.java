package com.mogum.course.dto;

import org.hibernate.validator.constraints.CodePointLength;

import jakarta.validation.constraints.NotBlank;

public record CourseTitleRequest(
	@NotBlank @CodePointLength(max = 100, message = "field.TOO_LONG") String title) {
}
