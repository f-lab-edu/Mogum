package com.mogum.account.service;

public sealed interface LoginResult {
    record Success(long userId) implements LoginResult {
    }

    record NotApplied(ExternalAccount account) implements LoginResult {
    }

    record Rejected(RejectionReason reason) implements LoginResult {
    }

    enum RejectionReason {
        EMAIL_NOT_VERIFIED
    }
}
