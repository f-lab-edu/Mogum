package com.mogum.api.account;

import com.mogum.core.account.User;
import com.mogum.core.account.UserRole;
import com.mogum.core.account.UserStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class MeControllerTest {
    @Test
    void 현재_사용자의_공개_필드와_약관_동의를_변환한다() {
        var user = new User(1L, "me@example.com", UserStatus.ACTIVE,
                UserRole.ADMIN, Instant.now(), "v1", Instant.now());
        var response = new MeController().me(user);
        assertEquals("me@example.com", response.email());
        assertEquals(UserStatus.ACTIVE, response.status());
        assertEquals(UserRole.ADMIN, response.role());
        assertTrue(response.termsAgreed());
        assertNull(response.waitlistPosition());
    }
}
