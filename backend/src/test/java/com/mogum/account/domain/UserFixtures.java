package com.mogum.account.domain;

import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

public final class UserFixtures {
    private UserFixtures() { }

    public static User user(String email) {
        return user("google", email, email);
    }

    public static User user(String provider, String subject, String email) {
        return User.apply(provider, subject, email, email.split("@", 2)[0], 100);
    }

    public static User withId(User user, long id) {
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    public static User withStatus(User user, UserStatus status) {
        ReflectionTestUtils.setField(user, "status", status);
        Instant approvedAt = status == UserStatus.APPROVED ? Instant.parse("2026-11-08T00:00:00Z") : null;
        ReflectionTestUtils.setField(user, "approvedAt", approvedAt);
        ReflectionTestUtils.setField(user, "creditRefillAt", approvedAt == null ? null : approvedAt.plusSeconds(30L * 86400));
        return user;
    }

    // 회원 정보 변경 기능 없이 JPA dirty checking과 flush를 검증할 때만 쓴다.
    public static User withEmail(User user, String email) {
        ReflectionTestUtils.setField(user, "email", email);
        return user;
    }
}
