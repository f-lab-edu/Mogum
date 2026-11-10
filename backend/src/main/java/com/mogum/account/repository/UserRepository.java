package com.mogum.account.repository;

import com.mogum.account.domain.User;

import org.springframework.data.repository.Repository;

import java.util.Optional;

public interface UserRepository extends Repository<User, Long>, UserIdentityLock {
    <S extends User> S save(S entity);

    Optional<User> findById(Long id);

    Optional<User> findByProviderAndSubject(String provider, String subject);
}
