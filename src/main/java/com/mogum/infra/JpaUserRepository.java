package com.mogum.infra;

import com.mogum.core.account.User;
import com.mogum.core.account.UserRepository;
import com.mogum.core.account.UserRole;
import com.mogum.core.account.UserStatus;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public class JpaUserRepository implements UserRepository {
    private final EntityManager entityManager;

    public JpaUserRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Optional<User> findById(long id) {
        return Optional.ofNullable(entityManager.find(UserEntity.class, id)).map(UserEntity::toUser);
    }

    @Override
    public Optional<User> findByGoogleSub(String googleSub) {
        return entityManager.createQuery("SELECT u FROM UserEntity u WHERE u.googleSub = :sub", UserEntity.class)
                .setParameter("sub", googleSub).getResultList().stream().findFirst().map(UserEntity::toUser);
    }

    @Override
    public User create(String googleSub, String email, UserStatus status, UserRole role, Instant createdAt) {
        var entity = new UserEntity(googleSub, email, status, role, createdAt);
        entityManager.persist(entity);
        return entity.toUser();
    }

    @Override
    public User updateLogin(long id, String email, UserRole role) {
        var entity = entityManager.find(UserEntity.class, id);
        if (entity == null) {
            throw new IllegalStateException("User no longer exists: " + id);
        }
        entity.updateLogin(email, role);
        return entity.toUser();
    }
}
