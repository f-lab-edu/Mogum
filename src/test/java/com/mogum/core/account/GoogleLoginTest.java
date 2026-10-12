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

class GoogleLoginTest {
    private final UserRepository repository = mock(UserRepository.class);
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
    private GoogleLogin login;

    @BeforeEach
    void setUp() {
        login = new GoogleLogin(repository, runner, Clock.fixed(now, ZoneOffset.UTC),
                List.of("  ADMIN@example.com  ", "second@example.com"));
    }

    @Test
    void 미인증은_관리자여도_DB_접근_전에_거절한다() {
        assertEquals(new LoginResult.Rejected(LoginResult.RejectionReason.EMAIL_NOT_VERIFIED),
                login.login(new GoogleAccount("sub", "admin@example.com", false)));
        verifyNoInteractions(repository);
        assertEquals(0, transactions);
    }

    @Test
    void 목록_밖_이메일은_기존_사용자_조회도_하지_않는다() {
        assertEquals(new LoginResult.Rejected(LoginResult.RejectionReason.NOT_ALLOWED),
                login.login(new GoogleAccount("existing-sub", "other@example.com", true)));
        verifyNoInteractions(repository);
        assertEquals(0, transactions);
    }

    @Test
    void 빈_목록과_공백_항목은_아무도_허용하지_않는다() {
        for (var emails : List.of(List.<String>of(), List.of("", "  "))) {
            var emptyLogin = new GoogleLogin(repository, runner, Clock.fixed(now, ZoneOffset.UTC), emails);
            assertEquals(new LoginResult.Rejected(LoginResult.RejectionReason.NOT_ALLOWED),
                    emptyLogin.login(new GoogleAccount("sub", "admin@example.com", true)));
        }
        verifyNoInteractions(repository);
    }

    @Test
    void 첫_로그인은_한_트랜잭션에서_ACTIVE_ADMIN과_주입한_시각으로_생성한다() {
        String email = "  Admin@EXAMPLE.COM  ";
        when(repository.findByGoogleSub("new-sub")).thenAnswer(invocation -> {
            assertTrue(inTransaction);
            return Optional.empty();
        });
        when(repository.create("new-sub", email, UserStatus.ACTIVE, UserRole.ADMIN, now))
                .thenAnswer(invocation -> {
                    assertTrue(inTransaction);
                    return new User(7, "new-sub", email, UserStatus.ACTIVE, UserRole.ADMIN, null, null, now);
                });

        assertEquals(new LoginResult.Success(7), login.login(new GoogleAccount("new-sub", email, true)));
        verify(repository).create("new-sub", email, UserStatus.ACTIVE, UserRole.ADMIN, now);
        verify(repository, never()).updateLogin(anyLong(), anyString(), any());
        assertEquals(1, transactions);
        assertFalse(inTransaction);
    }

    @ParameterizedTest
    @EnumSource(UserStatus.class)
    void 재로그인은_sub로_찾고_같은_id의_이메일과_역할만_갱신한다(UserStatus status) {
        var existing = new User(12, "stable-sub", "old@example.com", status, UserRole.USER,
                now.minusSeconds(100), "terms-v1", now.minusSeconds(200));
        when(repository.findByGoogleSub("stable-sub")).thenAnswer(invocation -> {
            assertTrue(inTransaction);
            return Optional.of(existing);
        });
        when(repository.updateLogin(12, "second@example.com", UserRole.ADMIN)).thenAnswer(invocation -> {
            assertTrue(inTransaction);
            return new User(12, existing.googleSub(), "second@example.com", existing.status(), UserRole.ADMIN,
                    existing.termsAgreedAt(), existing.termsVersion(), existing.createdAt());
        });

        assertEquals(new LoginResult.Success(12),
                login.login(new GoogleAccount("stable-sub", "second@example.com", true)));
        verify(repository).updateLogin(12, "second@example.com", UserRole.ADMIN);
        verify(repository, never()).create(anyString(), anyString(), any(), any(), any());
        assertEquals(1, transactions);
    }

    @Test
    void 역할_계산은_로그인_거절과_독립적으로_ADMIN과_USER를_반환한다() {
        assertEquals(UserRole.ADMIN, login.roleFor(" ADMIN@Example.COM "));
        assertEquals(UserRole.ADMIN, login.roleFor("second@example.com"));
        assertEquals(UserRole.USER, login.roleFor("other@example.com"));
        var emptyLogin = new GoogleLogin(repository, runner, Clock.fixed(now, ZoneOffset.UTC), List.of());
        assertEquals(UserRole.USER, emptyLogin.roleFor("admin@example.com"));
        verifyNoInteractions(repository);
    }
}
