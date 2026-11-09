package com.mogum.account.service;

public sealed interface LoginResult {
    record Success(long userId) implements LoginResult {
    }

    record NotApplied(ExternalAccount account) implements LoginResult {
    }

    record Rejected(RejectionReason reason) implements LoginResult {
    }

    /** 로그인 서비스가 로그인을 거절한 까닭. */
    enum RejectionReason {
        /** 이메일이 인증되지 않아 DB 조회 전에 거절하고, 콜백에서 로그인 실패로 리다이렉트한다. */
        EMAIL_NOT_VERIFIED
    }
}
