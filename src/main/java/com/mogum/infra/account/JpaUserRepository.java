package com.mogum.infra.account;

import com.mogum.core.account.User;
import com.mogum.core.account.UserRepository;
import com.mogum.core.account.UserRole;
import com.mogum.core.account.UserStatus;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public class JpaUserRepository implements UserRepository {
    private final SpringDataUserRepository repository;

    public JpaUserRepository(SpringDataUserRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<User> findById(long id) {
        return repository.findById(id).map(UserEntity::toUser);
    }

    @Override
    public User create(String email, UserStatus status, UserRole role, Instant createdAt) {
        var entity = new UserEntity(email, status, role, createdAt);
        return repository.save(entity).toUser();
    }
}
