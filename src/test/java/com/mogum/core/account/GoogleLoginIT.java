package com.mogum.core.account;

import com.mogum.core.TransactionRunner;
import com.mogum.support.PostgresTestConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class GoogleLoginIT extends PostgresTestConfiguration {
    @Autowired private UserRepository repository;
    @Autowired private TransactionRunner transactions;
    @PersistenceContext private EntityManager entityManager;
    private final String sub = "google-login-" + UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-12T03:00:00Z");

    @AfterEach
    void cleanUp() {
        transactions.inTransaction(() -> {
            entityManager.createNativeQuery("DELETE FROM users WHERE google_sub = :sub")
                    .setParameter("sub", sub).executeUpdate();
        });
    }

    @Test
    void 미인증과_목록_밖과_빈_목록은_DB에_행을_만들지_않는다() {
        var login = login(List.of("admin@example.com"));
        assertEquals(new LoginResult.Rejected(LoginResult.RejectionReason.EMAIL_NOT_VERIFIED),
                login.login(new GoogleAccount(sub, "admin@example.com", false)));
        assertAbsent();
        assertEquals(new LoginResult.Rejected(LoginResult.RejectionReason.NOT_ALLOWED),
                login.login(new GoogleAccount(sub, "outside@example.com", true)));
        assertAbsent();
        assertEquals(new LoginResult.Rejected(LoginResult.RejectionReason.NOT_ALLOWED),
                login(List.of()).login(new GoogleAccount(sub, "admin@example.com", true)));
        assertAbsent();
    }

    @Test
    void 기존_행이_있어도_거절하면_어느_컬럼도_바뀌지_않는다() {
        var before = transactions.inTransaction(() -> repository.create(
                sub, "old@example.com", UserStatus.WAITLISTED, UserRole.USER, now.minusSeconds(200)));
        transactions.inTransaction(() -> {
            entityManager.createNativeQuery("UPDATE users SET terms_agreed_at = :agreed, terms_version = 'v1' WHERE id = :id")
                    .setParameter("agreed", now.minusSeconds(100)).setParameter("id", before.id()).executeUpdate();
        });
        var snapshot = find();
        var login = login(List.of("admin@example.com"));
        assertEquals(new LoginResult.Rejected(LoginResult.RejectionReason.NOT_ALLOWED),
                login.login(new GoogleAccount(sub, "outside@example.com", true)));
        assertEquals(snapshot, find());
        assertEquals(new LoginResult.Rejected(LoginResult.RejectionReason.EMAIL_NOT_VERIFIED),
                login.login(new GoogleAccount(sub, "admin@example.com", false)));
        assertEquals(snapshot, find());
        assertEquals(new LoginResult.Rejected(LoginResult.RejectionReason.NOT_ALLOWED),
                login(List.of()).login(new GoogleAccount(sub, "admin@example.com", true)));
        assertEquals(snapshot, find());
    }

    @Test
    void 첫_로그인은_ACTIVE_ADMIN_약관_NULL_고정_시각을_저장하고_이메일_변경에도_id가_같다() {
        var login = login(List.of(" ADMIN@example.com ", "new@example.com"));
        var result = assertInstanceOf(LoginResult.Success.class,
                login.login(new GoogleAccount(sub, "Admin@EXAMPLE.COM", true)));
        var created = find();
        assertEquals(new User(result.userId(), sub, "Admin@EXAMPLE.COM", UserStatus.ACTIVE,
                UserRole.ADMIN, null, null, now), created);

        assertEquals(result, login.login(new GoogleAccount(sub, "new@example.com", true)));
        var updated = find();
        assertEquals(new User(created.id(), sub, "new@example.com", UserStatus.ACTIVE,
                UserRole.ADMIN, null, null, now), updated);
    }

    @Test
    void 재로그인은_저장된_상태와_약관과_가입_시각을_유지하고_역할은_다시_계산한다() {
        var before = transactions.inTransaction(() -> repository.create(
                sub, "old@example.com", UserStatus.WAITLISTED, UserRole.USER, now.minusSeconds(200)));
        transactions.inTransaction(() -> {
            entityManager.createNativeQuery("UPDATE users SET terms_agreed_at = :agreed, terms_version = 'v1' WHERE id = :id")
                    .setParameter("agreed", now.minusSeconds(100)).setParameter("id", before.id()).executeUpdate();
        });
        var snapshot = find();
        assertEquals(new LoginResult.Success(before.id()),
                login(List.of("admin@example.com")).login(new GoogleAccount(sub, "admin@example.com", true)));
        assertEquals(new User(snapshot.id(), sub, "admin@example.com", snapshot.status(), UserRole.ADMIN,
                snapshot.termsAgreedAt(), snapshot.termsVersion(), snapshot.createdAt()), find());
    }

    private GoogleLogin login(List<String> emails) {
        return new GoogleLogin(repository, transactions, Clock.fixed(now, ZoneOffset.UTC), emails);
    }

    private User find() {
        return transactions.inTransaction(() -> repository.findByGoogleSub(sub).orElseThrow());
    }

    private void assertAbsent() {
        assertTrue(transactions.inTransaction(() -> repository.findByGoogleSub(sub)).isEmpty());
    }
}
