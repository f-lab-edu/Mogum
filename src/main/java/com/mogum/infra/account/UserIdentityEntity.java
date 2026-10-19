package com.mogum.infra.account;

import com.mogum.core.account.ExternalIdentity;
import com.mogum.core.account.UserIdentity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "user_identities")
public class UserIdentityEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(nullable = false, columnDefinition = "text")
    private String provider;

    @Column(nullable = false, columnDefinition = "text")
    private String subject;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UserIdentityEntity() {
    }

    UserIdentityEntity(long userId, ExternalIdentity identity, Instant createdAt) {
        this.userId = userId;
        this.provider = identity.provider();
        this.subject = identity.subject();
        this.createdAt = createdAt;
    }

    UserIdentity toUserIdentity() {
        return new UserIdentity(id, userId, new ExternalIdentity(provider, subject), createdAt);
    }
}
