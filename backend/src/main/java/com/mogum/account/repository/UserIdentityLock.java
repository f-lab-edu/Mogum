package com.mogum.account.repository;

public interface UserIdentityLock {
    void lockIdentity(String provider, String subject);
}
