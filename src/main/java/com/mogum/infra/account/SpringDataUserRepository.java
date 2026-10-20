package com.mogum.infra.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface SpringDataUserRepository extends JpaRepository<UserEntity, Long> {
    Optional<UserEntity> findByProviderAndSubject(String provider, String subject);
}
