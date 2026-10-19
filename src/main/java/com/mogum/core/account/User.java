package com.mogum.core.account;

import java.time.Instant;

public record User(long id, String email, UserStatus status, UserRole role,
                   Instant termsAgreedAt, String termsVersion, Instant createdAt) {
}
