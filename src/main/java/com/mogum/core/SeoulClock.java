package com.mogum.core;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

public final class SeoulClock {
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private final Clock clock;

    public SeoulClock(Clock clock) {
        this.clock = clock;
    }

    public Instant now() {
        return clock.instant();
    }

    public LocalDate today() {
        return now().atZone(ZONE).toLocalDate();
    }

    public Instant startOfToday() {
        return today().atStartOfDay(ZONE).toInstant();
    }

    public Instant startOfTomorrow() {
        return today().plusDays(1).atStartOfDay(ZONE).toInstant();
    }
}
