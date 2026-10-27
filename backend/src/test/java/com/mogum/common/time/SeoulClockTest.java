package com.mogum.common.time;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SeoulClockTest {
    @ParameterizedTest
    @CsvSource({
            "2026-10-12T14:59:59Z, 2026-10-12, 2026-10-11T15:00:00Z, 2026-10-12T15:00:00Z",
            "2026-10-12T15:00:00Z, 2026-10-13, 2026-10-12T15:00:00Z, 2026-10-13T15:00:00Z",
            "2026-10-11T23:59:59Z, 2026-10-12, 2026-10-11T15:00:00Z, 2026-10-12T15:00:00Z",
            "2026-10-12T00:00:00Z, 2026-10-12, 2026-10-11T15:00:00Z, 2026-10-12T15:00:00Z"
    })
    void 한국_날짜와_자정_경계를_계산한다(String now, String today, String todayStart, String tomorrowStart) {
        var clock = new SeoulClock(Clock.fixed(Instant.parse(now), ZoneOffset.UTC));

        assertEquals(ZoneId.of("Asia/Seoul"), SeoulClock.ZONE);
        assertEquals(Instant.parse(now), clock.now());
        assertEquals(LocalDate.parse(today), clock.today());
        assertEquals(Instant.parse(todayStart), clock.startOfToday());
        assertEquals(Instant.parse(tomorrowStart), clock.startOfTomorrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "America/Los_Angeles", "Pacific/Kiritimati"})
    void JVM과_주입된_시계의_시간대와_무관하게_한국_날짜를_쓴다(String zone) {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            var now = Instant.parse("2026-10-12T15:00:00Z");
            var clock = new SeoulClock(Clock.fixed(now, ZoneId.of(zone)));

            assertEquals(now, clock.now());
            assertEquals(LocalDate.of(2026, 10, 13), clock.today());
            assertEquals(now, clock.startOfToday());
            assertEquals(Instant.parse("2026-10-13T15:00:00Z"), clock.startOfTomorrow());
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
