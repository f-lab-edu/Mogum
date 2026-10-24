package com.mogum.account.controller;

import com.mogum.account.domain.User;
import com.mogum.account.domain.UserRole;
import com.mogum.account.domain.UserStatus;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class MeControllerTest {
    @Test
    void 현재_사용자의_공개_필드와_약관_동의를_변환한다() {
        var user = User.join("google", "sub", "me@example.com", UserRole.ADMIN, Instant.now());
        ReflectionTestUtils.setField(user, "termsAgreedAt", Instant.now());
        var response = new MeController().me(user);
        assertEquals("me@example.com", response.email());
        assertEquals(UserStatus.ACTIVE, response.status());
        assertEquals(UserRole.ADMIN, response.role());
        assertTrue(response.termsAgreed());
        assertNull(response.waitlistPosition());
    }
}
