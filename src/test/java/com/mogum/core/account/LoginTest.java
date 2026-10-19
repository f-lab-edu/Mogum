package com.mogum.core.account;

import com.mogum.core.TransactionRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoginTest {
    private final UserRepository repository = mock(UserRepository.class);
    private final UserIdentityRepository identities = mock(UserIdentityRepository.class);
    private final Instant now = Instant.parse("2026-10-12T03:00:00Z");
    private boolean inTransaction;
    private int transactions;
    private final TransactionRunner runner = new TransactionRunner() {
        @Override
        public <T> T inTransaction(Supplier<T> work) {
            transactions++;
            inTransaction = true;
            try {
                return work.get();
            } finally {
                inTransaction = false;
            }
        }

        @Override
        public void inTransaction(Runnable work) {
            inTransaction(() -> { work.run(); return null; });
        }
    };
    private Login login;

    @BeforeEach
    void setUp() {
        login = new Login(repository, identities, runner, Clock.fixed(now, ZoneOffset.UTC),
                List.of("  ADMIN@example.com  ", "second@example.com"));
    }

    @Test
    void 미인증은_관리자여도_DB_접근_전에_거절한다() {
        assertEquals(new LoginResult.Rejected(LoginResult.RejectionReason.EMAIL_NOT_VERIFIED),
                login.login(account("sub", "admin@example.com", false)));
        verifyNoInteractions(repository, identities);
        assertEquals(0, transactions);
    }

    @Test
    void 목록_밖_이메일은_기존_사용자_조회도_하지_않는다() {
        assertEquals(new LoginResult.Rejected(LoginResult.RejectionReason.NOT_ALLOWED),
                login.login(account("existing-sub", "other@example.com", true)));
        verifyNoInteractions(repository, identities);
        assertEquals(0, transactions);
    }

    @Test
    void 빈_목록과_공백_항목은_아무도_허용하지_않는다() {
        for (var emails : List.of(List.<String>of(), List.of("", "  "))) {
            var emptyLogin = new Login(repository, identities, runner, Clock.fixed(now, ZoneOffset.UTC), emails);
            assertEquals(new LoginResult.Rejected(LoginResult.RejectionReason.NOT_ALLOWED),
                    emptyLogin.login(account("sub", "admin@example.com", true)));
        }
        verifyNoInteractions(repository, identities);
    }

    @Test
    void 첫_로그인은_한_트랜잭션에서_ACTIVE_ADMIN과_주입한_시각으로_생성한다() {
        String email = "  Admin@EXAMPLE.COM  ";
        when(identities.findByIdentity(identity("new-sub"))).thenAnswer(invocation -> {
            assertTrue(inTransaction);
            return Optional.empty();
        });
        when(repository.create(email, UserStatus.ACTIVE, UserRole.ADMIN, now))
                .thenAnswer(invocation -> {
                    assertTrue(inTransaction);
                    return new User(7, email, UserStatus.ACTIVE, UserRole.ADMIN, null, null, now);
                });

        assertEquals(new LoginResult.Success(7), login.login(account("new-sub", email, true)));
        verify(repository).create(email, UserStatus.ACTIVE, UserRole.ADMIN, now);
        verify(identities).findByIdentity(identity("new-sub"));
        verify(identities).create(7, identity("new-sub"), now);
        verifyNoMoreInteractions(identities);
        verifyNoMoreInteractions(repository);
        assertEquals(1, transactions);
        assertFalse(inTransaction);
    }

    @ParameterizedTest
    @EnumSource(UserStatus.class)
    void 재로그인은_외부_식별자로_찾고_기존_사용자_ID로_로그인하며_계정을_쓰지_않는다(UserStatus status) {
        when(identities.findByIdentity(identity("stable-sub"))).thenReturn(
                Optional.of(new UserIdentity(3, 12, identity("stable-sub"), now.minusSeconds(200))));
        var existing = new User(12, "old@example.com", status, UserRole.USER,
                now.minusSeconds(100), "terms-v1", now.minusSeconds(200));
        when(repository.findById(12)).thenAnswer(invocation -> {
            assertTrue(inTransaction);
            return Optional.of(existing);
        });
        assertEquals(new LoginResult.Success(12),
                login.login(account("stable-sub", "second@example.com", true)));
        verify(repository).findById(12);
        verify(identities).findByIdentity(identity("stable-sub"));
        verifyNoMoreInteractions(identities);
        verifyNoMoreInteractions(repository);
        assertEquals(1, transactions);
        assertFalse(inTransaction);
    }

    @Test
    void 역할_계산은_로그인_거절과_독립적으로_ADMIN과_USER를_반환한다() {
        assertEquals(UserRole.ADMIN, login.roleFor(" ADMIN@Example.COM "));
        assertEquals(UserRole.ADMIN, login.roleFor("second@example.com"));
        assertEquals(UserRole.USER, login.roleFor("other@example.com"));
        var emptyLogin = new Login(repository, identities, runner, Clock.fixed(now, ZoneOffset.UTC), List.of());
        assertEquals(UserRole.USER, emptyLogin.roleFor("admin@example.com"));
        verifyNoInteractions(repository, identities);
    }

    @Test
    void 연결이_있지만_회원이_사라졌으면_재가입하지_않는다() {
        when(identities.findByIdentity(identity("deleted"))).thenReturn(
                Optional.of(new UserIdentity(3, 12, identity("deleted"), now)));
        when(repository.findById(12)).thenReturn(Optional.empty());
        assertEquals(new LoginResult.Rejected(LoginResult.RejectionReason.ACCOUNT_NOT_FOUND),
                login.login(account("deleted", "admin@example.com", true)));
        verify(repository).findById(12);
        verify(identities).findByIdentity(identity("deleted"));
        verifyNoMoreInteractions(repository, identities);
    }

    private static ExternalIdentity identity(String subject) {
        return new ExternalIdentity("google", subject);
    }

    private static ExternalAccount account(String subject, String email, boolean verified) {
        return new ExternalAccount(identity(subject), email, verified);
    }
}
