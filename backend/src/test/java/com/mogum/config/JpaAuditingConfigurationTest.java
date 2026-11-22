package com.mogum.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JpaAuditingConfigurationTest {
	@Test
	@DisplayName("시각 공급자는 주입한 고정 Clock의 시각을 반환한다")
	void returnsInstantFromInjectedClock() {
		// given
		var fixed = Clock.fixed(Instant.parse("2026-10-28T01:02:03.123456Z"), ZoneOffset.UTC);
		var provider = new JpaAuditingConfiguration().auditingDateTimeProvider(fixed);

		// when
		var now = provider.getNow();

		// then
		assertThat(now).contains(fixed.instant());
	}
}
