package com.mogum.config;

import com.mogum.common.time.SeoulClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ClockConfigurationTest {
    @Test
    @DisplayName("UTC 시스템 시계와 한국 시계를 빈으로 등록한다")
    void registersSystemUtcAndSeoulClocks() {
        // given
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(ClockConfiguration.class);

            // when
            context.refresh();

            // then
            assertThat(context.getBean(Clock.class)).isEqualTo(Clock.systemUTC());
            assertThat(context.getBeansOfType(SeoulClock.class)).hasSize(1);
            var clock = context.getBean(SeoulClock.class);
            Instant before = Instant.now();
            Instant now = clock.now();
            Instant after = Instant.now();
            assertThat(now).isBetween(before, after);
        }
    }

    @Test
    @DisplayName("테스트의 고정 Clock을 한국 시계에 주입할 수 있다")
    void injectsFixedClockIntoSeoulClock() {
        // given
        Clock fixed = Clock.fixed(Instant.parse("2026-10-12T15:00:00Z"), ZoneOffset.UTC);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("fixedClock", Clock.class, () -> fixed, definition -> definition.setPrimary(true));
            context.register(ClockConfiguration.class);

            // when
            context.refresh();

            // then
            assertThat(context.getBean(Clock.class)).isSameAs(fixed);
            var clock = context.getBean(SeoulClock.class);
            assertThat(clock.now()).isEqualTo(fixed.instant());
            assertThat(clock.today()).isEqualTo(LocalDate.of(2026, 10, 13));
            assertThat(clock.startOfToday()).isEqualTo(fixed.instant());
            assertThat(clock.startOfTomorrow()).isEqualTo(Instant.parse("2026-10-13T15:00:00Z"));
        }
    }
}
