package com.mogum.account.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class UserTest {
    @Test
    void 첫_로그인으로_가입한_회원은_ACTIVE로_시작하고_약관_동의와_ID가_없다() {
        var joinedAt = Instant.parse("2026-10-12T03:00:00Z");

        var user = User.join("google", "sub-1", "me@example.com", UserRole.ADMIN, joinedAt);

        assertNull(user.getId());
        assertEquals("google", user.getProvider());
        assertEquals("sub-1", user.getSubject());
        assertEquals("me@example.com", user.getEmail());
        assertEquals(UserStatus.ACTIVE, user.getStatus());
        assertEquals(UserRole.ADMIN, user.getRole());
        assertNull(user.getTermsAgreedAt());
        assertNull(user.getTermsVersion());
        assertEquals(joinedAt, user.getCreatedAt());
    }
}
