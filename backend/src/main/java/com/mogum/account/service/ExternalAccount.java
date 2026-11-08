package com.mogum.account.service;

public record ExternalAccount(String provider, String subject, String email, String name, boolean emailVerified) {
}
