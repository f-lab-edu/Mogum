package com.mogum.infra;

import com.mogum.core.TransactionRunner;
import com.mogum.core.account.User;
import com.mogum.core.account.UserRepository;
import com.mogum.core.account.UserRole;
import com.mogum.core.account.UserStatus;
import com.mogum.support.PostgresTestConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class JpaUserRepositoryIT extends PostgresTestConfiguration {
    @Autowired private UserRepository repository;
    @Autowired private TransactionRunner transactions;
    @PersistenceContext private EntityManager entityManager;
    private final String sub = "repository-" + UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-12T03:00:00Z");

    @AfterEach
    void cleanUp() {
        transactions.inTransaction(() -> {
            entityManager.createNativeQuery("DELETE FROM users WHERE google_sub = :sub")
                    .setParameter("sub", sub).executeUpdate();
        });
    }

    @Test
    void 저장하고_id와_sub로_완전히_채운_record를_조회한다() {
        assertTrue(transactions.inTransaction(() -> repository.findByGoogleSub(sub)).isEmpty());
        assertTrue(transactions.inTransaction(() -> repository.findById(-1)).isEmpty());
        var saved = create();
        assertTrue(saved.id() > 0);
        var byId = transactions.inTransaction(() -> repository.findById(saved.id()).orElseThrow());
        var bySub = transactions.inTransaction(() -> repository.findByGoogleSub(sub).orElseThrow());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(new User(saved.id(), sub, "before@example.com", UserStatus.ACTIVE,
                UserRole.ADMIN, null, null, now), byId);
        assertEquals(byId, bySub);
    }

    @Test
    void 이메일과_역할_갱신은_나머지_컬럼을_보존한다() {
        var saved = create();
        var updated = transactions.inTransaction(() -> repository.updateLogin(saved.id(), "after@example.com", UserRole.USER));
        var expected = new User(saved.id(), sub, "after@example.com", saved.status(), UserRole.USER,
                saved.termsAgreedAt(), saved.termsVersion(), saved.createdAt());
        assertEquals(expected, updated);
        assertEquals(expected, transactions.inTransaction(() -> repository.findById(saved.id()).orElseThrow()));
    }

    @Test
    void google_sub는_유일하다() {
        var saved = create();
        assertSqlState("23505", assertThrows(RuntimeException.class, this::create));
        assertEquals(saved, transactions.inTransaction(() -> repository.findByGoogleSub(sub).orElseThrow()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"status", "role"})
    void CHECK가_허용하지_않은_상태와_역할을_거절한다(String column) {
        var saved = create();
        var failure = assertThrows(RuntimeException.class, () -> transactions.inTransaction(() -> {
            entityManager.createNativeQuery("UPDATE users SET " + column + " = 'INVALID' WHERE id = :id")
                    .setParameter("id", saved.id()).executeUpdate();
        }));
        assertSqlState("23514", failure);
        assertEquals(saved, transactions.inTransaction(() -> repository.findById(saved.id()).orElseThrow()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"google_sub", "email", "status", "role", "created_at"})
    void 필수_컬럼은_NULL을_거절한다(String column) {
        var saved = create();
        var failure = assertThrows(RuntimeException.class, () -> transactions.inTransaction(() -> {
            entityManager.createNativeQuery("UPDATE users SET " + column + " = NULL WHERE id = :id")
                    .setParameter("id", saved.id()).executeUpdate();
        }));
        assertSqlState("23502", failure);
        assertEquals(saved, transactions.inTransaction(() -> repository.findById(saved.id()).orElseThrow()));
    }

    @Test
    void JPA_변경을_flush하면_같은_트랜잭션의_네이티브_조회에_보인다() {
        var saved = create();
        User snapshot = transactions.inTransaction(() -> {
            var entity = entityManager.find(UserEntity.class, saved.id());
            entity.updateLogin("flushed@example.com", UserRole.USER);
            entityManager.flush();
            assertEquals("flushed@example.com", nativeEmail(saved.id()));
            return repository.findById(saved.id()).orElseThrow();
        });
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(new User(saved.id(), sub, "flushed@example.com", saved.status(), UserRole.USER,
                null, null, now), snapshot);
    }

    @Test
    void 명시적_flush_없는_네이티브_조회_값을_관찰한다() {
        var saved = create();
        transactions.inTransaction(() -> {
            entityManager.find(UserEntity.class, saved.id()).updateLogin("auto-flush@example.com", UserRole.USER);
            // 자동 flush 여부는 설정에 의존하므로 조회 결과는 관찰만 한다.
            System.out.println("users native email without explicit flush: " + nativeEmail(saved.id()));
        });
    }

    private User create() {
        return transactions.inTransaction(() -> repository.create(sub, "before@example.com", UserStatus.ACTIVE, UserRole.ADMIN, now));
    }

    private String nativeEmail(long id) {
        return (String) entityManager.createNativeQuery("SELECT email FROM users WHERE id = :id")
                .setParameter("id", id).getSingleResult();
    }

    private static void assertSqlState(String expected, Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                assertEquals(expected, sqlException.getSQLState());
                return;
            }
        }
        fail("PostgreSQL constraint failure was expected", failure);
    }
}
