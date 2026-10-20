package com.mogum.account.service;

import com.mogum.account.domain.ExternalIdentity;

public record ExternalAccount(ExternalIdentity identity, String email, boolean emailVerified) {
}
