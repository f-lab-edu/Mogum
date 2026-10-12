package com.mogum.api.account;

import com.mogum.core.account.UserRole;
import com.mogum.core.account.UserStatus;

public record MeResponse(String email, UserStatus status, UserRole role,
                         boolean termsAgreed, Integer waitlistPosition) {
}
