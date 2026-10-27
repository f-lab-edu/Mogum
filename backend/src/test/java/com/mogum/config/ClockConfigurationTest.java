package com.mogum.config;

import com.mogum.common.time.SeoulClock;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class ClockConfigurationTest {
    @Test
    void UTC_시스템_시계와_한국_시계를_빈으로_등록한다() {
        try (var context = new AnnotationConfigApplicationContext(ClockConfiguration.class)) {
            assertEquals(Clock.systemUTC(), context.getBean(Clock.class));
            assertEquals(1, context.getBeansOfType(SeoulClock.class).size());
            var clock = context.getBean(SeoulClock.class);
            Instant before = Instant.now();
            Instant now = clock.now();
            Instant after = Instant.now();

            assertFalse(now.isBefore(before));
            assertFalse(now.isAfter(after));
        }
    }

    @Test
    void 테스트의_고정_Clock을_한국_시계에_주입할_수_있다() {
        Clock fixed = Clock.fixed(Instant.parse("2026-10-12T15:00:00Z"), ZoneOffset.UTC);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("fixedClock", Clock.class, () -> fixed, definition -> definition.setPrimary(true));
            context.register(ClockConfiguration.class);
            context.refresh();

            assertSame(fixed, context.getBean(Clock.class));
            var clock = context.getBean(SeoulClock.class);
            assertEquals(fixed.instant(), clock.now());
            assertEquals(LocalDate.of(2026, 10, 13), clock.today());
            assertEquals(fixed.instant(), clock.startOfToday());
            assertEquals(Instant.parse("2026-10-13T15:00:00Z"), clock.startOfTomorrow());
        }
    }
}
