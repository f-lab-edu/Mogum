package com.mogum.account.repository;

import com.mogum.account.domain.CreditEntry;
import com.mogum.account.domain.CreditEntryType;
import com.mogum.account.domain.UserFixtures;
import com.mogum.support.RepositoryTest;
import com.mogum.support.TestClock;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Hibernate;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@RepositoryTest
class CreditEntryRepositoryTest {
    @Autowired private UserRepository users;
    @Autowired private CreditEntryRepository entries;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestClock clock;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    @Test
    @DisplayName("채움 기록은 회원 연결과 금액 및 Auditing 시각을 저장하고 회원은 지연 로딩한다")
    void persistsRefillWithAuditingAndLazyUser() {
        // given
        var user = users.save(UserFixtures.user("me@example.com"));
        var saved = entries.save(CreditEntry.refill(user, 100));

        // when
        var actual = new TransactionTemplate(transactionManager).execute(status ->
                entityManager.find(CreditEntry.class, saved.getId()));

        // then
        assertThat(actual).isNotNull();
        assertThat(actual.getId()).isPositive();
        assertThat(actual.getType()).isEqualTo(CreditEntryType.REFILL);
        assertThat(actual.getAmount()).isEqualTo(100);
        assertThat(actual.getCreatedAt()).isEqualTo(clock.instant());
        assertThat(actual.getUpdatedAt()).isEqualTo(clock.instant());
        assertThat(actual.getUser().getId()).isEqualTo(user.getId());
        assertThat(Hibernate.isInitialized(actual.getUser())).isFalse();
        assertThatThrownBy(() -> actual.getUser().getEmail()).isInstanceOf(LazyInitializationException.class);
        assertThat(jdbc.queryForObject("SELECT sum(amount) FROM credit_entries WHERE user_id = ?", Integer.class,
                user.getId())).isEqualTo(user.getCreditBalance());
    }

    @Test
    @DisplayName("알 수 없는 크레딧 기록 종류는 DB가 거절한다")
    void rejectsUnknownEntryType() {
        // given
        var user = users.save(UserFixtures.user("me@example.com"));

        // when & then
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO credit_entries (user_id, type, amount, created_at, updated_at)
                VALUES (?, 'INVALID', 100, now(), now())
                """, user.getId())).hasMessageContaining("credit_entries_type_check");
    }

    @ParameterizedTest(name = "종류 {0}")
    @ValueSource(strings = {"REFILL", "CONSUME", "RESTORE"})
    @DisplayName("계약의 크레딧 기록 종류는 DB가 받아들인다")
    void acceptsContractEntryTypes(String type) {
        // given
        var user = users.save(UserFixtures.user("me@example.com"));

        // when
        int inserted = jdbc.update("""
                INSERT INTO credit_entries (user_id, type, amount, created_at, updated_at)
                VALUES (?, ?, 0, now(), now())
                """, user.getId(), type);

        // then
        assertThat(inserted).isEqualTo(1);
    }

    @Test
    @DisplayName("회원 삭제는 채움 기록을 자동으로 지우지 않고 FK로 막힌다")
    void preventsDeletingUserBeforeCreditEntries() {
        // given
        var user = users.save(UserFixtures.user("me@example.com"));
        entries.save(CreditEntry.refill(user, 100));

        // when & then
        assertThatThrownBy(() -> jdbc.update("DELETE FROM users WHERE id = ?", user.getId()))
                .hasMessageContaining("credit_entries_user_id_fkey");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_entries", Integer.class)).isEqualTo(1);
    }
}
