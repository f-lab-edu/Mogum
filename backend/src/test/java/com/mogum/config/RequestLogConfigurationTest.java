package com.mogum.config;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class RequestLogConfigurationTest {
    @Test
    void 요청_필터와_종료_리스너를_같은_인스턴스로_등록한다() {
        var config = new RequestLogConfiguration();
        var registration = config.requestLogFilter();
        assertEquals(Ordered.HIGHEST_PRECEDENCE, registration.getOrder());
        assertEquals(java.util.EnumSet.of(DispatcherType.REQUEST, DispatcherType.ERROR),
                registration.determineDispatcherTypes());
        assertSame(registration.getFilter(), config.requestLogCompletion(registration).getListener());
    }
}
