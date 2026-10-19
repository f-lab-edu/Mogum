package com.mogum.core.account;

public record ExternalAccount(ExternalIdentity identity, String email, boolean emailVerified) {
}
