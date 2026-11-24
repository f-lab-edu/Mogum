package com.mogum.common.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public final class SeoulClock {
	public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

	private final Clock clock;

	public Instant now() {
		return clock.instant();
	}

	public LocalDate today() {
		return now().atZone(ZONE).minusHours(4).toLocalDate();
	}

	public Instant startOfToday() {
		return today().atTime(4, 0).atZone(ZONE).toInstant();
	}

	public Instant startOfTomorrow() {
		return today().plusDays(1).atTime(4, 0).atZone(ZONE).toInstant();
	}
}
