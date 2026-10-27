package com.mogum.common.persistence;

import com.mogum.account.domain.User;
import com.mogum.account.domain.UserRole;
import com.mogum.account.repository.UserRepository;
import com.mogum.support.PostgresTestConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@Import(AuditedEntityIT.TestClockConfiguration.class)
class AuditedEntityIT extends PostgresTestConfiguration {
    private static final Instant CREATED = Instant.parse("2026-10-28T01:02:03.123456Z");
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Clock clock;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;
    private final String subject = "audit-" + UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(CREATED);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE provider = 'google' AND subject = ?", subject);
    }

    @Test
    void 저장하면_생성과_수정_시각을_주입한_시계로_채운다() {
        var saved = save();

        assertTimes(saved.getId(), CREATED, CREATED);
    }

    @Test
    void 변경을_커밋하면_수정_시각만_새_시각으로_바뀐다() {
        var saved = save();
        Instant updated = CREATED.plusSeconds(60);
        when(clock.instant()).thenReturn(updated);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var user = entityManager.find(User.class, saved.getId());
            ReflectionTestUtils.setField(user, "email", "changed@example.com");
        });

        assertEquals("changed@example.com", users.findById(saved.getId()).orElseThrow().getEmail());
        assertTimes(saved.getId(), CREATED, updated);
    }

    private User save() {
        return users.save(User.join("google", subject, "audit@example.com", UserRole.USER));
    }

    private void assertTimes(long id, Instant created, Instant updated) {
        var reloaded = users.findById(id).orElseThrow();
        assertEquals(created, reloaded.getCreatedAt());
        assertEquals(updated, reloaded.getUpdatedAt());
        jdbc.queryForObject("SELECT created_at, updated_at FROM users WHERE id = ?", (row, index) -> {
            assertEquals(created, row.getTimestamp("created_at").toInstant());
            assertEquals(updated, row.getTimestamp("updated_at").toInstant());
            return id;
        }, id);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfiguration {
        @Bean
        @Primary
        Clock auditingTestClock() {
            return mock(Clock.class);
        }
    }
}
