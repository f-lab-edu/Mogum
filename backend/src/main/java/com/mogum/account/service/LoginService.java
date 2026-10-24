package com.mogum.account.service;

import com.mogum.account.domain.User;
import com.mogum.account.domain.UserRole;
import com.mogum.account.repository.UserRepository;
import com.mogum.config.AuthProperties;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class LoginService {
    private final UserRepository users;
    private final Clock clock;
    private final Set<String> adminEmails;

    public LoginService(UserRepository users, Clock clock, AuthProperties properties) {
        this.users = users;
        this.clock = clock;
        this.adminEmails = properties.adminEmails().stream().map(LoginService::normalizeEmail)
                .filter(email -> !email.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    // 거절은 DB 접근 전에 한다(02 §6.1). 조회와 저장은 저장소의 짧은 트랜잭션이고,
    // 동시 첫 로그인은 (provider, subject) unique 제약이 하나만 남긴다.
    public LoginResult login(ExternalAccount account) {
        if (!account.emailVerified()) {
            return new LoginResult.Rejected(LoginResult.RejectionReason.EMAIL_NOT_VERIFIED);
        }
        // ADR-062: 공개 가입 흐름을 넣을 때 이 임시 거절 분기만 제거한다.
        if (!adminEmails.contains(normalizeEmail(account.email()))) {
            return new LoginResult.Rejected(LoginResult.RejectionReason.NOT_ALLOWED);
        }

        var existing = users.findByProviderAndSubject(account.provider(), account.subject());
        if (existing.isPresent()) {
            return new LoginResult.Success(existing.get().getId());
        }
        var user = users.save(User.join(account.provider(), account.subject(), account.email(),
                roleFor(account.email()), clock.instant()));
        return new LoginResult.Success(user.getId());
    }

    public UserRole roleFor(String email) {
        return adminEmails.contains(normalizeEmail(email)) ? UserRole.ADMIN : UserRole.USER;
    }

    private static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
