package com.mogum.account.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UserTest {
	@Test
	@DisplayName("채움은 잔액과 다음 시각을 함께 바꾼다")
	void refillsCreditsAndMovesNextDate() {
		// given
		var user = UserFixtures.approved("me@example.com", 40, java.time.Instant.parse("2026-11-07T19:00:00Z"));
		var next = java.time.Instant.parse("2026-11-14T19:00:00Z");

		// when
		user.refillCredits(25, next);

		// then
		assertThat(user.getCreditBalance()).isEqualTo(25);
		assertThat(user.getCreditRefillAt()).isEqualTo(next);
	}

	@Test
	@DisplayName("신청은 신원과 이름과 크레딧을 보존하고 APPLIED로 시작하며 승인 시각은 비어 있다")
	void createsAppliedUserWithProvidedIdentityAndCredit() {
		// when
		var user = User.apply("google", "sub-1", "me@example.com", "신청자", 123);

		// then
		assertThat(user.getId()).isNull();
		assertThat(user.getCreatedAt()).isNull();
		assertThat(user.getUpdatedAt()).isNull();
		assertThat(user.getProvider()).isEqualTo("google");
		assertThat(user.getSubject()).isEqualTo("sub-1");
		assertThat(user.getEmail()).isEqualTo("me@example.com");
		assertThat(user.getName()).isEqualTo("신청자");
		assertThat(user.getCreditBalance()).isEqualTo(123);
		assertThat(user.getStatus()).isEqualTo(UserStatus.APPLIED);
		assertThat(user.getApprovedAt()).isNull();
		assertThat(user.getCreditRefillAt()).isNull();
	}
}
