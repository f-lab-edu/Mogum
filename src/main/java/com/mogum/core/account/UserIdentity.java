package com.mogum.core.account;

import java.time.Instant;

public record UserIdentity(long id, long userId, ExternalIdentity identity, Instant createdAt) {
}
