package com.mogum.common.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.TimeZone;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SeoulClockTest {
	@Test
	@DisplayName("한국 날짜 계산 시간대는 Asia/Seoul이다")
	void definesSeoulZone() {
		// when
		var zone = SeoulClock.ZONE;

		// then
		assertThat(zone).isEqualTo(ZoneId.of("Asia/Seoul"));
	}

	@ParameterizedTest(name = "현재 {0} 예상 {1}")
	@CsvSource({
		"2026-10-12T14:59:59Z, 2026-10-12T14:59:59Z",
		"2026-10-12T15:00:00Z, 2026-10-12T15:00:00Z",
		"2026-10-11T23:59:59Z, 2026-10-11T23:59:59Z",
		"2026-10-12T00:00:00Z, 2026-10-12T00:00:00Z"
	})
	@DisplayName("현재 시각을 그대로 반환한다")
	void returnsInjectedInstant(String now, String expected) {
		// given
		var clock = new SeoulClock(Clock.fixed(Instant.parse(now), ZoneOffset.UTC));

		// when
		var actual = clock.now();

		// then
		assertThat(actual).isEqualTo(Instant.parse(expected));
	}

	@ParameterizedTest(name = "현재 {0} 예상 {1}")
	@CsvSource({
		"2026-10-12T18:59:59Z, 2026-10-12",
		"2026-10-12T19:00:00Z, 2026-10-13",
		"2026-10-12T16:00:00Z, 2026-10-12",
		"2026-10-12T14:59:59Z, 2026-10-12",
		"2026-12-31T18:59:59Z, 2026-12-31"
	})
	@DisplayName("새벽 4시 전에는 전날이고 4시부터 그날이다")
	void calculatesSeoulDate(String now, String expected) {
		// given
		var clock = new SeoulClock(Clock.fixed(Instant.parse(now), ZoneOffset.UTC));

		// when
		var actual = clock.today();

		// then
		assertThat(actual).isEqualTo(LocalDate.parse(expected));
	}

	@ParameterizedTest(name = "현재 {0} 예상 {1}")
	@CsvSource({
		"2026-10-12T18:59:59Z, 2026-10-11T19:00:00Z",
		"2026-10-12T19:00:00Z, 2026-10-12T19:00:00Z",
		"2026-10-12T16:00:00Z, 2026-10-11T19:00:00Z",
		"2026-10-12T14:59:59Z, 2026-10-11T19:00:00Z",
		"2026-12-31T18:59:59Z, 2026-12-30T19:00:00Z"
	})
	@DisplayName("한국의 오늘 새벽 4시 경계를 계산한다")
	void calculatesStartOfToday(String now, String expected) {
		// given
		var clock = new SeoulClock(Clock.fixed(Instant.parse(now), ZoneOffset.UTC));

		// when
		var actual = clock.startOfToday();

		// then
		assertThat(actual).isEqualTo(Instant.parse(expected));
	}

	@ParameterizedTest(name = "현재 {0} 예상 {1}")
	@CsvSource({
		"2026-10-12T18:59:59Z, 2026-10-12T19:00:00Z",
		"2026-10-12T19:00:00Z, 2026-10-13T19:00:00Z",
		"2026-10-12T16:00:00Z, 2026-10-12T19:00:00Z",
		"2026-10-12T14:59:59Z, 2026-10-12T19:00:00Z",
		"2026-12-31T18:59:59Z, 2026-12-31T19:00:00Z"
	})
	@DisplayName("한국의 내일 새벽 4시 경계를 계산한다")
	void calculatesStartOfTomorrow(String now, String expected) {
		// given
		var clock = new SeoulClock(Clock.fixed(Instant.parse(now), ZoneOffset.UTC));

		// when
		var actual = clock.startOfTomorrow();

		// then
		assertThat(actual).isEqualTo(Instant.parse(expected));
	}

	@ParameterizedTest(name = "JVM과 시계 시간대 {0}")
	@ValueSource(strings = {"UTC", "America/Los_Angeles", "Pacific/Kiritimati"})
	@DisplayName("JVM과 주입된 시계의 시간대와 무관하게 현재 시각을 그대로 반환한다")
	void returnsInjectedInstantRegardlessOfClockAndJvmZone(String zone) {
		// given
		TimeZone original = TimeZone.getDefault();
		try {
			TimeZone.setDefault(TimeZone.getTimeZone(zone));
			var now = Instant.parse("2026-10-12T15:00:00Z");
			var clock = new SeoulClock(Clock.fixed(now, ZoneId.of(zone)));

			// when
			var actual = clock.now();

			// then
			assertThat(actual).isEqualTo(now);
		} finally {
			TimeZone.setDefault(original);
		}
	}

	@ParameterizedTest(name = "JVM과 시계 시간대 {0}")
	@ValueSource(strings = {"UTC", "America/Los_Angeles", "Pacific/Kiritimati"})
	@DisplayName("JVM과 주입된 시계의 시간대와 무관하게 한국 날짜를 계산한다")
	void calculatesSeoulDateRegardlessOfClockAndJvmZone(String zone) {
		// given
		TimeZone original = TimeZone.getDefault();
		try {
			TimeZone.setDefault(TimeZone.getTimeZone(zone));
			var now = Instant.parse("2026-10-12T15:00:00Z");
			var clock = new SeoulClock(Clock.fixed(now, ZoneId.of(zone)));

			// when
			var actual = clock.today();

			// then
			assertThat(actual).isEqualTo(LocalDate.of(2026, 10, 12));
		} finally {
			TimeZone.setDefault(original);
		}
	}

	@ParameterizedTest(name = "JVM과 시계 시간대 {0}")
	@ValueSource(strings = {"UTC", "America/Los_Angeles", "Pacific/Kiritimati"})
	@DisplayName("JVM과 주입된 시계의 시간대와 무관하게 한국의 오늘 새벽 4시 경계를 계산한다")
	void calculatesStartOfTodayRegardlessOfClockAndJvmZone(String zone) {
		// given
		TimeZone original = TimeZone.getDefault();
		try {
			TimeZone.setDefault(TimeZone.getTimeZone(zone));
			var now = Instant.parse("2026-10-12T15:00:00Z");
			var clock = new SeoulClock(Clock.fixed(now, ZoneId.of(zone)));

			// when
			var actual = clock.startOfToday();

			// then
			assertThat(actual).isEqualTo(Instant.parse("2026-10-11T19:00:00Z"));
		} finally {
			TimeZone.setDefault(original);
		}
	}

	@ParameterizedTest(name = "JVM과 시계 시간대 {0}")
	@ValueSource(strings = {"UTC", "America/Los_Angeles", "Pacific/Kiritimati"})
	@DisplayName("JVM과 주입된 시계의 시간대와 무관하게 한국의 내일 새벽 4시 경계를 계산한다")
	void calculatesStartOfTomorrowRegardlessOfClockAndJvmZone(String zone) {
		// given
		TimeZone original = TimeZone.getDefault();
		try {
			TimeZone.setDefault(TimeZone.getTimeZone(zone));
			var now = Instant.parse("2026-10-12T15:00:00Z");
			var clock = new SeoulClock(Clock.fixed(now, ZoneId.of(zone)));

			// when
			var actual = clock.startOfTomorrow();

			// then
			assertThat(actual).isEqualTo(Instant.parse("2026-10-12T19:00:00Z"));
		} finally {
			TimeZone.setDefault(original);
		}
	}
}
