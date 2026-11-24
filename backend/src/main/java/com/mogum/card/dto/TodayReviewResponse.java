package com.mogum.card.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mogum.card.domain.Rating;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TodayReviewResponse(List<CardItem> cards, int remainingCount, String courseTitle) {
	public record CardItem(long id, String question, String answer, Map<Rating, Long> nextIntervalsSeconds) {
	}
}
