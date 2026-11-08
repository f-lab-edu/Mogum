package com.mogum.config;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;

import static org.assertj.core.api.Assertions.assertThat;

class RequestLogConfigurationTest {
    @Test
    @DisplayName("요청 필터는 최외곽 순서로 REQUEST와 ERROR 디스패치에 등록한다")
    void registersFilterForRequestAndErrorDispatches() {
        // given
        var config = new RequestLogConfiguration();

        // when
        var registration = config.requestLogFilter();

        // then
        assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(registration.determineDispatcherTypes())
                .containsExactlyInAnyOrder(DispatcherType.REQUEST, DispatcherType.ERROR);
    }

    @Test
    @DisplayName("요청 필터와 종료 리스너를 같은 인스턴스로 등록한다")
    void registersSameFilterAsCompletionListener() {
        // given
        var config = new RequestLogConfiguration();
        var registration = config.requestLogFilter();

        // when
        var listener = config.requestLogCompletion(registration);

        // then
        assertThat(listener.getListener()).isSameAs(registration.getFilter());
    }
}
