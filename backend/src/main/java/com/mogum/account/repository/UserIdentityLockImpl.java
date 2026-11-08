package com.mogum.account.repository;

import jakarta.persistence.EntityManager;

/** 행이 없는 신청 전 신원도 트랜잭션이 끝날 때까지 DB에서 직렬화한다. */
public class UserIdentityLockImpl implements UserIdentityLock {
    private final EntityManager entityManager;

    public UserIdentityLockImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public void lockIdentity(String provider, String subject) {
        // 해시 충돌은 서로 다른 신원을 잠시 직렬화할 뿐, 신원 일치 판단은 정확한 두 칸으로 한다.
        entityManager.createNativeQuery("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:identity, 0))")
                .setParameter("identity", "account.apply:" + provider + ":" + subject)
                .getSingleResult();
    }
}
