package com.mogum.infra.account;

import com.mogum.core.account.User;
import com.mogum.core.account.ExternalIdentity;
import com.mogum.core.account.UserRole;
import com.mogum.core.account.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(
        name = "users_provider_subject_key", columnNames = {"provider", "subject"}))
public class UserEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "text")
    private String provider;

    @Column(nullable = false, columnDefinition = "text")
    private String subject;

    @Column(nullable = false, columnDefinition = "text")
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private UserStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private UserRole role;

    @Column(name = "terms_agreed_at")
    private Instant termsAgreedAt;

    @Column(name = "terms_version", columnDefinition = "text")
    private String termsVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UserEntity() {
    }

    UserEntity(ExternalIdentity identity, String email, UserStatus status, UserRole role, Instant createdAt) {
        this.provider = identity.provider();
        this.subject = identity.subject();
        this.email = email;
        this.status = status;
        this.role = role;
        this.createdAt = createdAt;
    }

    User toUser() {
        return new User(id, email, status, role, termsAgreedAt, termsVersion, createdAt);
    }
}
