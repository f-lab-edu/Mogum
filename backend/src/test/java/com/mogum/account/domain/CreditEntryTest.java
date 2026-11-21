package com.mogum.account.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CreditEntryTest {
	@Test
	@DisplayName("씀 기록은 작업 ID와 회원을 보존하고 양수 크기를 음수로 담는다")
	void createsConsumeWithNegativeAmount() {
		// given
		var user = UserFixtures.user("me@example.com");

		// when
		var entry = CreditEntry.consume(user, 73L, 12);

		// then
		assertThat(entry.getUser()).isSameAs(user);
		assertThat(entry.getJobId()).isEqualTo(73L);
		assertThat(entry.getType()).isEqualTo(CreditEntryType.CONSUME);
		assertThat(entry.getAmount()).isEqualTo(-12);
		assertThat(entry.getCreatedAt()).isNull();
	}

	@Test
	@DisplayName("복구 기록은 작업 ID와 회원과 양수 크기를 보존한다")
	void createsRestoreWithPositiveAmount() {
		// given
		var user = UserFixtures.user("me@example.com");

		// when
		var entry = CreditEntry.restore(user, 73L, 12);

		// then
		assertThat(entry.getUser()).isSameAs(user);
		assertThat(entry.getJobId()).isEqualTo(73L);
		assertThat(entry.getType()).isEqualTo(CreditEntryType.RESTORE);
		assertThat(entry.getAmount()).isEqualTo(12);
		assertThat(entry.getCreatedAt()).isNull();
	}

	@ParameterizedTest(name = "크기 {0}")
	@ValueSource(ints = {0, -1, Integer.MIN_VALUE})
	@DisplayName("씀과 복구 팩토리는 양수가 아닌 크기를 거절한다")
	void rejectsNonPositiveAmount(int amount) {
		// given
		var user = UserFixtures.user("me@example.com");

		// when & then
		assertThatThrownBy(() -> CreditEntry.consume(user, 73L, amount)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> CreditEntry.restore(user, 73L, amount)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("채움 기록은 회원과 금액을 보존하고 REFILL로 시작한다")
	void createsRefillEntryWithUserAndAmount() {
		// given
		var user = UserFixtures.user("me@example.com");

		// when
		var entry = CreditEntry.refill(user, 123);

		// then
		assertThat(entry.getId()).isNull();
		assertThat(entry.getCreatedAt()).isNull();
		assertThat(entry.getUpdatedAt()).isNull();
		assertThat(entry.getUser()).isSameAs(user);
		assertThat(entry.getType()).isEqualTo(CreditEntryType.REFILL);
		assertThat(entry.getJobId()).isNull();
		assertThat(entry.getAmount()).isEqualTo(123);
	}
}
