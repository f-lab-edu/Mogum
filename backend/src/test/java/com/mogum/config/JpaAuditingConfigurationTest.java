package com.mogum.config;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JpaAuditingConfigurationTest {
    @Test
    void 시각_공급자는_주입한_고정_Clock의_시각을_반환한다() {
        var fixed = Clock.fixed(Instant.parse("2026-10-28T01:02:03.123456Z"), ZoneOffset.UTC);
        var provider = new JpaAuditingConfiguration().auditingDateTimeProvider(fixed);

        assertEquals(fixed.instant(), provider.getNow().orElseThrow());
    }
}
