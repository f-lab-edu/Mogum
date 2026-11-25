package com.mogum.card.service;

import java.util.List;

import com.mogum.card.domain.Judgment;

final class MasteryJudgmentCalculator {
	private static final double WELL_KNOWN_RETRIEVABILITY = 0.9;
	private static final double WELL_KNOWN_STABILITY_DAYS = 21;

	// @checkstyle:off — 포매터가 빈 선언과 블록을 한 줄로 유지한다.
	private MasteryJudgmentCalculator() {}
	// @checkstyle:on

	static Judgment judge(List<CardMemory> cards) {
		if (cards.isEmpty()) {
			return Judgment.NO_RECORD;
		}
		double retrievability = cards.stream().mapToDouble(CardMemory::retrievability).average().orElseThrow();
		double stability = cards.stream().mapToDouble(CardMemory::stability).average().orElseThrow();
		return retrievability >= WELL_KNOWN_RETRIEVABILITY && stability >= WELL_KNOWN_STABILITY_DAYS
			? Judgment.WELL_KNOWN : Judgment.SHAKY;
	}

	record CardMemory(double retrievability, double stability) {
	}
}
