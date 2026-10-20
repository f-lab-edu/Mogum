package com.mogum.account.service;

public record ExternalAccount(String provider, String subject, String email, boolean emailVerified) {
}
