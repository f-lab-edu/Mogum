package com.mogum.infra.account;

import com.mogum.core.account.ExternalIdentity;
import com.mogum.core.account.UserIdentity;
import com.mogum.core.account.UserIdentityRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public class JpaUserIdentityRepository implements UserIdentityRepository {
    private final SpringDataUserIdentityRepository repository;

    public JpaUserIdentityRepository(SpringDataUserIdentityRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<UserIdentity> findByIdentity(ExternalIdentity identity) {
        return repository.findByProviderAndSubject(identity.provider(), identity.subject())
                .map(UserIdentityEntity::toUserIdentity);
    }

    @Override
    public UserIdentity create(long userId, ExternalIdentity identity, Instant createdAt) {
        return repository.save(new UserIdentityEntity(userId, identity, createdAt)).toUserIdentity();
    }
}
