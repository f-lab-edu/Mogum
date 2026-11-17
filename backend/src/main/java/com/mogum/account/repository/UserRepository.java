package com.mogum.account.repository;

import com.mogum.account.domain.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.util.Optional;

public interface UserRepository extends Repository<User, Long>, UserIdentityLock {
    <S extends User> S save(S entity);

    Optional<User> findById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(long id);

    Optional<User> findByProviderAndSubject(String provider, String subject);
}
