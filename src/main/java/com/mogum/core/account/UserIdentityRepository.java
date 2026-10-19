package com.mogum.core.account;

import java.time.Instant;
import java.util.Optional;

public interface UserIdentityRepository {
    Optional<UserIdentity> findByIdentity(ExternalIdentity identity);

    UserIdentity create(long userId, ExternalIdentity identity, Instant createdAt);
}
