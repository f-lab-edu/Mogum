package com.mogum.account.dto;

import com.mogum.account.domain.UserRole;
import com.mogum.account.domain.UserStatus;

public record MeResponse(String email, UserStatus status, UserRole role,
                         boolean termsAgreed, Integer waitlistPosition) {
}
