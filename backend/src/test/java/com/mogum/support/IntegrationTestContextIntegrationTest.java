package com.mogum.support;

import com.mogum.common.time.SeoulClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestContextManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class IntegrationTestContextIntegrationTest {
    @Autowired private ApplicationContext context;
    @Autowired private TestClock clock;
    @Autowired private SeoulClock seoulClock;

    @Test
    @DisplayName("서로 다른 통합 테스트 클래스는 같은 스프링 컨텍스트를 재사용한다")
    void sharesContextWithAnotherTestClass() {
        // when
        var other = new TestContextManager(IntegrationTestSharedContextIntegrationTest.class)
                .getTestContext().getApplicationContext();

        // then
        assertThat(other).isSameAs(context);
    }

    @Test
    @DisplayName("운영 시계 빈을 덮어쓰지 않고 공용 테스트 시계가 서울 날짜에도 적용된다")
    void suppliesPrimaryClockToSeoulClock() {
        // given
        clock.set(Instant.parse("2026-11-09T16:00:00Z"));

        // when
        var today = seoulClock.today();

        // then
        assertThat(today).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(context.getBean(Clock.class)).isSameAs(clock);
        assertThat(context.getBean("testClock")).isSameAs(clock);
        assertThat(context.getBean("clock")).isNotSameAs(clock);
    }
}
