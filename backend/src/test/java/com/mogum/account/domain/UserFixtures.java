package com.mogum.account.domain;

import org.springframework.test.util.ReflectionTestUtils;

public final class UserFixtures {
    private UserFixtures() { }

    public static User user(String email) {
        return user("google", email, email, UserRole.USER);
    }

    public static User user(String provider, String subject, String email, UserRole role) {
        return User.join(provider, subject, email, role);
    }

    public static User withId(User user, long id) {
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    public static User withStatus(User user, UserStatus status) {
        ReflectionTestUtils.setField(user, "status", status);
        return user;
    }

    // 회원 정보 변경 기능 없이 JPA dirty checking과 flush를 검증할 때만 쓴다.
    public static User withEmail(User user, String email) {
        ReflectionTestUtils.setField(user, "email", email);
        return user;
    }
}
