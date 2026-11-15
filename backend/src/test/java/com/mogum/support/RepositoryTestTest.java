package com.mogum.support;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.account.domain.CreditEntry;
import com.mogum.account.repository.CreditEntryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Hibernate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@RepositoryTest
class RepositoryTestTest {
    @Autowired private UserRepository users;
    @Autowired private CreditEntryRepository credits;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;
    @Autowired private TestClock clock;

    @Test
    @DisplayName("레포지토리 테스트는 테스트 트랜잭션을 열지 않는다")
    void runsWithoutTestTransaction() {
        // when
        var active = TransactionSynchronizationManager.isActualTransactionActive();

        // then
        assertThat(active).isFalse();
    }

    @Test
    @DisplayName("저장한 엔티티의 생성과 수정 시각은 공용 테스트 시계의 시각이다")
    void auditsWithTestClock() {
        // given
        var instant = Instant.parse("2026-11-09T12:34:56.123456Z");
        clock.set(instant);
        var saved = users.save(UserFixtures.user("auditing@example.com"));

        // when
        var reloaded = users.findById(saved.getId()).orElseThrow();

        // then
        assertThat(reloaded.getCreatedAt()).isEqualTo(instant);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(instant);
    }

    @Test
    @DisplayName("커밋 후 다시 읽은 크레딧 기록의 지연 연관은 트랜잭션 밖에서 초기화되지 않는다")
    void rejectsLazyLoadingOutsideTransaction() {
        // given
        var user = users.save(UserFixtures.user("lazy@example.com"));
        var credit = credits.save(CreditEntry.refill(user, 100));
        var reloaded = new TransactionTemplate(transactionManager)
                .execute(status -> entityManager.find(CreditEntry.class, credit.getId()));
        assertThat(reloaded).isNotNull();
        assertThat(Hibernate.isInitialized(reloaded.getUser())).isFalse();

        // when & then
        assertThatThrownBy(() -> reloaded.getUser().getName())
                .isInstanceOf(LazyInitializationException.class);
    }
}
