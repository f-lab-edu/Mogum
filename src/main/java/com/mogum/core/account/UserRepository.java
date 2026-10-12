package com.mogum.core.account;

import java.time.Instant;
import java.util.Optional;

/** 호출자는 TransactionRunner 안에서 사용하며, 반환값은 완전히 채운 스냅샷이다. */
public interface UserRepository {
    Optional<User> findById(long id);

    Optional<User> findByGoogleSub(String googleSub);

    User create(String googleSub, String email, UserStatus status, UserRole role, Instant createdAt);

    /** 상태 · 약관 · 가입 시각은 보존한다. */
    User updateLogin(long id, String email, UserRole role);
}
