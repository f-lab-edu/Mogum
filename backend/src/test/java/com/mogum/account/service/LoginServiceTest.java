package com.mogum.account.service;

import com.mogum.account.domain.ExternalIdentity;
import com.mogum.account.domain.User;
import com.mogum.account.domain.UserRole;
import com.mogum.account.domain.UserStatus;
import com.mogum.account.repository.UserRepository;
import com.mogum.config.AuthProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LoginServiceTest {
    private final UserRepository repository = mock(UserRepository.class);
    private final Instant now = Instant.parse("2026-10-12T03:00:00Z");
    private LoginService login;

    @BeforeEach
    void setUp() {
        login = service(List.of("  ADMIN@example.com  ", "second@example.com"));
    }

    @Test
    void 미인증은_관리자여도_DB_접근_전에_거절한다() {
        assertEquals(new LoginResult.Rejected(LoginResult.RejectionReason.EMAIL_NOT_VERIFIED),
                login.login(account("sub", "admin@example.com", false)));
        verifyNoInteractions(repository);
    }

    @Test
    void 목록_밖_이메일은_기존_사용자_조회도_하지_않는다() {
        assertEquals(new LoginResult.Rejected(LoginResult.RejectionReason.NOT_ALLOWED),
                login.login(account("existing-sub", "other@example.com", true)));
        verifyNoInteractions(repository);
    }

    @Test
    void 빈_목록과_공백_항목은_아무도_허용하지_않는다() {
        for (var emails : List.of(List.<String>of(), List.of("", "  "))) {
            assertEquals(new LoginResult.Rejected(LoginResult.RejectionReason.NOT_ALLOWED),
                    service(emails).login(account("sub", "admin@example.com", true)));
        }
        verifyNoInteractions(repository);
    }

    @Test
    void 첫_로그인은_ACTIVE_ADMIN과_주입한_시각으로_생성한다() {
        String email = "  Admin@EXAMPLE.COM  ";
        when(repository.findByProviderAndSubject("google", "new-sub")).thenReturn(Optional.empty());
        when(repository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            ReflectionTestUtils.setField(user, "id", 7L);
            return user;
        });

        assertEquals(new LoginResult.Success(7), login.login(account("new-sub", email, true)));
        var saved = ArgumentCaptor.forClass(User.class);
        verify(repository).findByProviderAndSubject("google", "new-sub");
        verify(repository).save(saved.capture());
        verifyNoMoreInteractions(repository);
        assertEquals(email, saved.getValue().getEmail());
        assertEquals(UserStatus.ACTIVE, saved.getValue().getStatus());
        assertEquals(UserRole.ADMIN, saved.getValue().getRole());
        assertEquals(now, saved.getValue().getCreatedAt());
    }

    @ParameterizedTest
    @EnumSource(UserStatus.class)
    void 재로그인은_외부_식별자로_찾고_기존_사용자_ID로_로그인하며_계정을_쓰지_않는다(UserStatus status) {
        var existing = new User(new ExternalIdentity("google", "stable-sub"), "old@example.com", status,
                UserRole.USER, now.minusSeconds(200));
        ReflectionTestUtils.setField(existing, "id", 12L);
        when(repository.findByProviderAndSubject("google", "stable-sub")).thenReturn(Optional.of(existing));

        assertEquals(new LoginResult.Success(12),
                login.login(account("stable-sub", "second@example.com", true)));
        verify(repository).findByProviderAndSubject("google", "stable-sub");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void 역할_계산은_로그인_거절과_독립적으로_ADMIN과_USER를_반환한다() {
        assertEquals(UserRole.ADMIN, login.roleFor(" ADMIN@Example.COM "));
        assertEquals(UserRole.ADMIN, login.roleFor("second@example.com"));
        assertEquals(UserRole.USER, login.roleFor("other@example.com"));
        assertEquals(UserRole.USER, service(List.of()).roleFor("admin@example.com"));
        verifyNoInteractions(repository);
    }

    private LoginService service(List<String> adminEmails) {
        return new LoginService(repository, Clock.fixed(now, ZoneOffset.UTC), new AuthProperties(adminEmails));
    }

    private static ExternalAccount account(String subject, String email, boolean verified) {
        return new ExternalAccount(new ExternalIdentity("google", subject), email, verified);
    }
}
