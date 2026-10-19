package com.mogum.infra.account;

import com.mogum.core.account.User;
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

import java.time.Instant;

@Entity
@Table(name = "users")
public class UserEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    UserEntity(String email, UserStatus status, UserRole role, Instant createdAt) {
        this.email = email;
        this.status = status;
        this.role = role;
        this.createdAt = createdAt;
    }

    User toUser() {
        return new User(id, email, status, role, termsAgreedAt, termsVersion, createdAt);
    }
}
