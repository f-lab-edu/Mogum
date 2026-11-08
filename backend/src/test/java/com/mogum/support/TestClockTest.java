package com.mogum.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class TestClockTest {
    @Test
    @DisplayName("시계는 UTC의 마이크로초 정밀도 상수 시각에서 시작하고 저절로 흐르지 않는다")
    void startsAtFixedUtcInstant() {
        // given
        var clock = new TestClock();

        // when
        var first = clock.instant();

        // then
        assertThat(first).isEqualTo(TestClock.INITIAL_INSTANT);
        assertThat(first.getNano() % 1_000).isZero();
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
        assertThat(clock.instant()).isEqualTo(first);
    }

    @Test
    @DisplayName("시각을 지정하면 지정한 시각을 돌려준다")
    void setsInstant() {
        // given
        var clock = new TestClock();
        var instant = Instant.parse("2026-11-09T15:00:00Z");

        // when
        clock.set(instant);

        // then
        assertThat(clock.instant()).isEqualTo(instant);
    }

    @Test
    @DisplayName("기간을 더하면 현재 시각에서 그만큼 이동한다")
    void advancesInstant() {
        // given
        var clock = new TestClock();

        // when
        clock.advance(Duration.ofHours(3));

        // then
        assertThat(clock.instant()).isEqualTo(TestClock.INITIAL_INSTANT.plus(Duration.ofHours(3)));
    }

    @Test
    @DisplayName("초기화하면 처음 시각으로 돌아간다")
    void resetsInstant() {
        // given
        var clock = new TestClock();
        clock.advance(Duration.ofDays(2));

        // when
        clock.reset();

        // then
        assertThat(clock.instant()).isEqualTo(TestClock.INITIAL_INSTANT);
    }

    @Test
    @DisplayName("시간대를 바꾼 시계도 같은 현재 시각을 따른다")
    void sharesInstantAcrossZones() {
        // given
        var clock = new TestClock();
        var seoul = clock.withZone(ZoneId.of("Asia/Seoul"));

        // when
        clock.advance(Duration.ofDays(1));

        // then
        assertThat(seoul.getZone()).isEqualTo(ZoneId.of("Asia/Seoul"));
        assertThat(seoul.instant()).isEqualTo(clock.instant());
    }
}
