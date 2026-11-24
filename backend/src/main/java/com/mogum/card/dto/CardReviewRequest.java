package com.mogum.card.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CardReviewRequest(
	@NotNull @Pattern(regexp = "AGAIN|HARD|GOOD|EASY", message = "field.INVALID_VALUE") String rating) {
}
