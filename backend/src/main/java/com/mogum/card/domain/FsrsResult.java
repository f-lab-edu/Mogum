package com.mogum.card.domain;

import java.time.Instant;

public record FsrsResult(FsrsState fsrsState, Integer fsrsStep, Double stability, Double difficulty,
	Instant dueAt, Instant lastReviewedAt) {
}
