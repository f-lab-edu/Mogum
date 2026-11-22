package com.mogum.job.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import jakarta.validation.Validation;

class JobReceiveRequestValidatorTest {
	@ParameterizedTest(name = "문자={0}")
	@ValueSource(strings = {"가", "😀"})
	@DisplayName("입력 상한은 PostgreSQL처럼 유니코드 글자 수로 검사하고 기타 글과 선택 메모를 허용한다")
	void acceptsCharacterLimits(String character) {
		// given
		var request = new JobReceiveRequest("NEW_COURSE", null, null, null,
			character.repeat(100), "OTHER", character.repeat(50),
			"OTHER", character.repeat(50), character.repeat(500), null);

		try (var factory = Validation.buildDefaultValidatorFactory()) {
			// when
			var violations = factory.getValidator().validate(request);

			// then
			assertThat(violations).isEmpty();
		}
	}

	@ParameterizedTest(name = "문자={0}")
	@ValueSource(strings = {"가", "😀"})
	@DisplayName("다시 짜기 선택 피드백도 500글자까지 허용한다")
	void acceptsFeedbackLimit(String character) {
		// given
		var request = new JobReceiveRequest("REPLAN_COURSE", 1L, null, null,
			null, null, null, null, null, null, character.repeat(500));

		try (var factory = Validation.buildDefaultValidatorFactory()) {
			// when
			var violations = factory.getValidator().validate(request);

			// then
			assertThat(violations).isEmpty();
		}
	}
}
