package com.mogum.account.repository;

import com.mogum.account.domain.User;
import com.mogum.account.domain.UserRole;
import com.mogum.account.domain.UserStatus;
import com.mogum.support.PostgresTestConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class UserRepositoryIT extends PostgresTestConfiguration {
    @Autowired private UserRepository repository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @PersistenceContext private EntityManager entityManager;
    private TransactionTemplate transactions;
    private final String email = "repository-" + UUID.randomUUID() + "@example.com";
    private final java.util.List<Long> ids = new java.util.ArrayList<>();
    private final Instant now = Instant.parse("2026-10-12T03:00:00Z");

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void cleanUp() {
        for (var id : ids) jdbc.update("DELETE FROM users WHERE id = ?", id);
    }

    @Test
    void 저장하고_id로_조회한다() {
        assertTrue(repository.findById(-1L).isEmpty());
        var saved = create();
        assertTrue(saved.getId() > 0);
        var byId = repository.findById(saved.getId()).orElseThrow();
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertSameUser(saved.getId(), email, UserStatus.ACTIVE, UserRole.ADMIN, now, byId);
        assertNull(byId.getTermsAgreedAt());
        assertNull(byId.getTermsVersion());
    }

    @Test
    void 호출자의_트랜잭션이_롤백되면_사용자_저장도_롤백된다() {
        var failure = new IllegalStateException("rollback user creation");

        var actual = assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status -> {
            var saved = repository.save(User.join("google", "Sub-" + UUID.randomUUID(), email, UserRole.ADMIN, now));
            ids.add(saved.getId());
            assertTrue(saved.getId() > 0);
            throw failure;
        }));

        assertSame(failure, actual);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertTrue(repository.findById(ids.getFirst()).isEmpty());
    }

    @Test
    void 이메일은_외부_계정의_키가_아니므로_중복을_허용한다() {
        var first = create();
        var second = create();
        assertNotEquals(first.getId(), second.getId());
        assertEquals(first.getEmail(), second.getEmail());
    }

    @Test
    void 외부_식별자는_원문으로_조회하며_중복과_빈값을_거절한다() {
        String sub = " Sub-" + UUID.randomUUID() + " ";
        assertTrue(find("google", sub).isEmpty());
        var saved = save("google", sub, UserRole.ADMIN);
        assertEquals(saved.getId(), find("google", sub).orElseThrow().getId());
        assertTrue(find("google", sub.strip()).isEmpty());
        assertTrue(find("google", sub.toLowerCase(java.util.Locale.ROOT)).isEmpty());
        var duplicate = assertThrows(RuntimeException.class, () ->
                repository.save(User.join("google", sub, email, UserRole.ADMIN, now)));
        assertSqlState("23505", duplicate);
        for (String blank : java.util.List.of("", "  ", "\t\n")) {
            var invalid = assertThrows(RuntimeException.class, () ->
                    repository.save(User.join("google", blank, email, UserRole.ADMIN, now)));
            assertSqlState("23514", invalid);
        }
        for (String blank : java.util.List.of("", "  ", "\t\n")) {
            var invalid = assertThrows(RuntimeException.class, () ->
                    repository.save(User.join(blank, sub, email, UserRole.ADMIN, now)));
            assertSqlState("23514", invalid);
        }
        assertEquals(saved.getId(), find("google", sub).orElseThrow().getId());
    }

    @Test
    void 제공자가_다르면_같은_subject도_별도_회원이다() {
        String subject = "shared-" + UUID.randomUUID();
        assertTrue(find("other-provider", subject).isEmpty());
        var first = save("google", subject, UserRole.USER);
        assertTrue(find("other-provider", subject).isEmpty());
        var second = save("other-provider", subject, UserRole.USER);
        assertNotEquals(first.getId(), second.getId());
        assertEquals(first.getId(), find("google", subject).orElseThrow().getId());
        assertEquals(second.getId(), find("other-provider", subject).orElseThrow().getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"status", "role"})
    void CHECK가_허용하지_않은_상태와_역할을_거절한다(String column) {
        var saved = create();
        var failure = assertThrows(RuntimeException.class, () ->
                jdbc.update("UPDATE users SET " + column + " = 'INVALID' WHERE id = ?", saved.getId()));
        assertSqlState("23514", failure);
        assertSameUser(saved.getId(), email, UserStatus.ACTIVE, UserRole.ADMIN, now,
                repository.findById(saved.getId()).orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"provider", "subject", "email", "status", "role", "created_at"})
    void 필수_컬럼은_NULL을_거절한다(String column) {
        var saved = create();
        var failure = assertThrows(RuntimeException.class, () ->
                jdbc.update("UPDATE users SET " + column + " = NULL WHERE id = ?", saved.getId()));
        assertSqlState("23502", failure);
        assertSameUser(saved.getId(), email, UserStatus.ACTIVE, UserRole.ADMIN, now,
                repository.findById(saved.getId()).orElseThrow());
    }

    @Test
    void JPA_변경을_flush하면_같은_트랜잭션의_네이티브_조회에_보인다() {
        var saved = create();
        String email = transactions.execute(status -> {
            var entity = entityManager.find(User.class, saved.getId());
            // 로그인용 갱신 메서드 없이 JPA와 네이티브 쿼리 사이의 flush 동작만 검증한다.
            ReflectionTestUtils.setField(entity, "email", "flushed@example.com");
            entityManager.flush();
            assertEquals("flushed@example.com", nativeEmail(saved.getId()));
            return repository.findById(saved.getId()).orElseThrow().getEmail();
        });
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals("flushed@example.com", email);
    }

    @Test
    void 명시적_flush_없는_네이티브_조회_값을_관찰한다() {
        var saved = create();
        transactions.executeWithoutResult(status -> {
            ReflectionTestUtils.setField(entityManager.find(User.class, saved.getId()),
                    "email", "auto-flush@example.com");
            // 자동 flush 여부는 설정에 의존하므로 조회 결과는 관찰만 한다.
            System.out.println("users native email without explicit flush: " + nativeEmail(saved.getId()));
        });
    }

    private User create() {
        return save("google", "Sub-" + UUID.randomUUID(), UserRole.ADMIN);
    }

    private User save(String provider, String subject, UserRole role) {
        var user = repository.save(User.join(provider, subject, email, role, now));
        ids.add(user.getId());
        return user;
    }

    private Optional<User> find(String provider, String subject) {
        return repository.findByProviderAndSubject(provider, subject);
    }

    private String nativeEmail(long id) {
        return (String) entityManager.createNativeQuery("SELECT email FROM users WHERE id = :id")
                .setParameter("id", id).getSingleResult();
    }

    private static void assertSameUser(long id, String email, UserStatus status, UserRole role, Instant createdAt, User actual) {
        assertEquals(id, actual.getId());
        assertEquals(email, actual.getEmail());
        assertEquals(status, actual.getStatus());
        assertEquals(role, actual.getRole());
        assertEquals(createdAt, actual.getCreatedAt());
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
