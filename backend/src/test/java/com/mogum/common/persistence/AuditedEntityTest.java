package com.mogum.common.persistence;

import com.mogum.account.domain.User;
import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.support.RepositoryTest;
import com.mogum.support.TestClock;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@RepositoryTest
class AuditedEntityTest {
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestClock clock;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    @Test
    @DisplayName("저장하면 생성과 수정 시각을 주입한 시계의 마이크로초 정밀도로 채운다")
    void fillsCreationAndModificationTimesOnSave() {
        // given
        var created = Instant.parse("2026-10-28T01:02:03.123456Z");
        clock.set(created);

        // when
        var saved = users.save(UserFixtures.user("audit@example.com"));

        // then
        assertTimes(saved.getId(), created, created);
    }

    @Test
    @DisplayName("변경을 커밋하면 생성 시각은 보존하고 수정 시각만 새 시각으로 바뀐다")
    void updatesOnlyModificationTimeOnCommit() {
        // given
        var created = Instant.parse("2026-10-28T01:02:03.123456Z");
        clock.set(created);
        var saved = users.save(UserFixtures.user("audit@example.com"));
        clock.advance(Duration.ofSeconds(60));
        var transactions = new TransactionTemplate(transactionManager);

        // when
        transactions.executeWithoutResult(status ->
                UserFixtures.withEmail(entityManager.find(User.class, saved.getId()), "changed@example.com"));

        // then
        assertThat(users.findById(saved.getId()).orElseThrow().getEmail()).isEqualTo("changed@example.com");
        assertTimes(saved.getId(), created, created.plusSeconds(60));
    }

    private void assertTimes(long id, Instant created, Instant updated) {
        var reloaded = users.findById(id).orElseThrow();
        assertThat(reloaded.getCreatedAt()).isEqualTo(created);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(updated);
        jdbc.queryForObject("SELECT created_at, updated_at FROM users WHERE id = ?", (row, index) -> {
            assertThat(row.getTimestamp("created_at").toInstant()).isEqualTo(created);
            assertThat(row.getTimestamp("updated_at").toInstant()).isEqualTo(updated);
            return id;
        }, id);
    }
}
