package com.mogum.auth;

import com.mogum.account.domain.UserStatus;

import java.io.Serializable;

/** 요청 인증의 두 종류. 회원 상태는 매 요청 DB에서 읽고, 신청 전 신원만 세션에 저장한다. */
public sealed interface AccountPrincipal {
    String NOT_APPLIED_SESSION_ATTRIBUTE = "mogum.notAppliedAccount";

    String email();

    String name();

    record Member(long userId, String email, String name, UserStatus status) implements AccountPrincipal { }

    record NotApplied(String provider, String subject, String email, String name)
            implements AccountPrincipal, Serializable { }
}
