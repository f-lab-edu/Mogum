package com.mogum.account.domain;

public final class UserFixtures {
    private UserFixtures() { }

    public static User user(String email) {
        return User.join("google", email, email, UserRole.USER);
    }
}
