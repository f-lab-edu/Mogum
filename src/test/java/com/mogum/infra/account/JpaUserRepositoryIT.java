package com.mogum.infra.account;

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
import org.springframework.test.util.ReflectionTestUtils;
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
    private final String email = "repository-" + UUID.randomUUID() + "@example.com";
    private final java.util.List<Long> ids = new java.util.ArrayList<>();
    private final Instant now = Instant.parse("2026-10-12T03:00:00Z");

    @AfterEach
    void cleanUp() {
        transactions.inTransaction(() -> {
            for (var id : ids) entityManager.createNativeQuery("DELETE FROM users WHERE id = :id")
                    .setParameter("id", id).executeUpdate();
        });
    }

    @Test
    void 저장하고_id로_완전히_채운_record를_조회한다() {
        assertTrue(transactions.inTransaction(() -> repository.findById(-1)).isEmpty());
        var saved = create();
        assertTrue(saved.id() > 0);
        var byId = transactions.inTransaction(() -> repository.findById(saved.id()).orElseThrow());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(new User(saved.id(), email, UserStatus.ACTIVE,
                UserRole.ADMIN, null, null, now), byId);
    }

    @Test
    void 호출자의_트랜잭션이_롤백되면_사용자_저장도_롤백된다() {
        var failure = new IllegalStateException("rollback user creation");

        var actual = assertThrows(IllegalStateException.class, () -> transactions.inTransaction(() -> {
            var saved = repository.create(email, UserStatus.ACTIVE, UserRole.ADMIN, now);
            ids.add(saved.id());
            assertTrue(saved.id() > 0);
            throw failure;
        }));

        assertSame(failure, actual);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertTrue(transactions.inTransaction(() -> repository.findById(ids.getFirst())).isEmpty());
    }

    @Test
    void 이메일은_외부_계정의_키가_아니므로_중복을_허용한다() {
        var first = create();
        var second = create();
        assertNotEquals(first.id(), second.id());
        assertEquals(first.email(), second.email());
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
    @ValueSource(strings = {"email", "status", "role", "created_at"})
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
            // 로그인용 갱신 메서드 없이 JPA와 네이티브 쿼리 사이의 flush 동작만 검증한다.
            ReflectionTestUtils.setField(entity, "email", "flushed@example.com");
            entityManager.flush();
            assertEquals("flushed@example.com", nativeEmail(saved.id()));
            return repository.findById(saved.id()).orElseThrow();
        });
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(new User(saved.id(), "flushed@example.com", saved.status(), saved.role(),
                null, null, now), snapshot);
    }

    @Test
    void 명시적_flush_없는_네이티브_조회_값을_관찰한다() {
        var saved = create();
        transactions.inTransaction(() -> {
            ReflectionTestUtils.setField(entityManager.find(UserEntity.class, saved.id()),
                    "email", "auto-flush@example.com");
            // 자동 flush 여부는 설정에 의존하므로 조회 결과는 관찰만 한다.
            System.out.println("users native email without explicit flush: " + nativeEmail(saved.id()));
        });
    }

    private User create() {
        var user = transactions.inTransaction(() -> repository.create(email, UserStatus.ACTIVE, UserRole.ADMIN, now));
        ids.add(user.id());
        return user;
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
