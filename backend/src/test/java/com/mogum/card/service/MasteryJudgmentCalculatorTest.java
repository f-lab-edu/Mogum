package com.mogum.card.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.mogum.card.domain.Judgment;
import com.mogum.card.service.MasteryJudgmentCalculator.CardMemory;

class MasteryJudgmentCalculatorTest {
	@Test
	@DisplayName("확률 평균 0.9와 단단함 평균 21일인 숙련도면 잘 앎으로 판정해 짧게 다룬다")
	void treatsExactThresholdsAsWellKnownForBriefCoverage() {
		// given
		var cards = List.of(new CardMemory(0.9, 21));

		// when
		var actual = MasteryJudgmentCalculator.judge(cards);

		// then
		assertThat(actual).isEqualTo(Judgment.WELL_KNOWN);
	}

	@ParameterizedTest(name = "확률={0}, 단단함={1}")
	@CsvSource({"0.899999,21", "0.9,20.999999", "1,1", "0.5,100", "0.8,20"})
	@DisplayName("확률과 단단함 가운데 하나라도 기준보다 모자라면 흔들림이다")
	void requiresBothThresholds(double probability, double stability) {
		// given
		var cards = List.of(new CardMemory(probability, stability));

		// when
		var actual = MasteryJudgmentCalculator.judge(cards);

		// then
		assertThat(actual).isEqualTo(Judgment.SHAKY);
	}

	@Test
	@DisplayName("센 카드가 없으면 기록 없음이다")
	void returnsNoRecordWithoutCards() {
		// when
		var actual = MasteryJudgmentCalculator.judge(List.of());

		// then
		assertThat(actual).isEqualTo(Judgment.NO_RECORD);
	}

	@Test
	@DisplayName("여러 카드의 확률과 단단함을 같은 무게로 평균해 잘 앎이면 짧게 다룬다")
	void averagesCardsWithEqualWeightForBriefCoverage() {
		// given
		var cards = List.of(new CardMemory(0.8, 10), new CardMemory(1.0, 32));

		// when
		var actual = MasteryJudgmentCalculator.judge(cards);

		// then
		assertThat(actual).isEqualTo(Judgment.WELL_KNOWN);
	}

	@Test
	@DisplayName("카드 하나가 잘 앎이어도 전체 평균이 모자라면 흔들림이다")
	void judgesByAverageRatherThanBestCard() {
		// given
		var cards = List.of(new CardMemory(1.0, 21), new CardMemory(0.7, 21));

		// when
		var actual = MasteryJudgmentCalculator.judge(cards);

		// then
		assertThat(actual).isEqualTo(Judgment.SHAKY);
	}
}
