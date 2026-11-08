package com.mogum.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/** 테스트가 옮길 때만 흐르는 시계. 실제 포트의 요청 스레드에도 변경을 공개한다. */
public final class TestClock extends Clock {
    public static final Instant INITIAL_INSTANT = Instant.parse("2026-11-08T00:00:00Z");

    private volatile Instant current = INITIAL_INSTANT;

    public void set(Instant instant) {
        current = Objects.requireNonNull(instant);
    }

    public void advance(Duration duration) {
        current = current.plus(duration);
    }

    public void reset() {
        current = INITIAL_INSTANT;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        Objects.requireNonNull(zone);
        if (getZone().equals(zone)) return this;
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return zone;
            }

            @Override
            public Clock withZone(ZoneId other) {
                return TestClock.this.withZone(other);
            }

            @Override
            public Instant instant() {
                return TestClock.this.instant();
            }
        };
    }

    @Override
    public Instant instant() {
        return current;
    }
}
