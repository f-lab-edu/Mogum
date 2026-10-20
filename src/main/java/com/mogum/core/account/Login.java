package com.mogum.core.account;

import com.mogum.core.TransactionRunner;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public final class Login {
    private final UserRepository repository;
    private final TransactionRunner transactions;
    private final Clock clock;
    private final Set<String> adminEmails;

    public Login(UserRepository repository, TransactionRunner transactions,
                 Clock clock, List<String> adminEmails) {
        this.repository = repository;
        this.transactions = transactions;
        this.clock = clock;
        this.adminEmails = adminEmails.stream().map(Login::normalizeEmail)
                .filter(email -> !email.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    public LoginResult login(ExternalAccount account) {
        if (!account.emailVerified()) {
            return new LoginResult.Rejected(LoginResult.RejectionReason.EMAIL_NOT_VERIFIED);
        }
        // ADR-062: 공개 가입 흐름을 넣을 때 이 임시 거절 분기만 제거한다.
        if (!adminEmails.contains(normalizeEmail(account.email()))) {
            return new LoginResult.Rejected(LoginResult.RejectionReason.NOT_ALLOWED);
        }

        return transactions.inTransaction(() -> {
            var existing = repository.findByIdentity(account.identity());
            if (existing.isPresent()) {
                return new LoginResult.Success(existing.get().id());
            }
            var now = clock.instant();
            var user = repository.create(account.identity(), account.email(), UserStatus.ACTIVE,
                    roleFor(account.email()), now);
            return new LoginResult.Success(user.id());
        });
    }

    public UserRole roleFor(String email) {
        return adminEmails.contains(normalizeEmail(email)) ? UserRole.ADMIN : UserRole.USER;
    }

    private static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
