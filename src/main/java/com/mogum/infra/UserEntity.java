package com.mogum.infra;

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

    @Column(name = "google_sub", nullable = false, unique = true, columnDefinition = "text")
    private String googleSub;

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

    UserEntity(String googleSub, String email, UserStatus status, UserRole role, Instant createdAt) {
        this.googleSub = googleSub;
        this.email = email;
        this.status = status;
        this.role = role;
        this.createdAt = createdAt;
    }

    void updateLogin(String email, UserRole role) {
        this.email = email;
        this.role = role;
    }

    User toUser() {
        return new User(id, googleSub, email, status, role, termsAgreedAt, termsVersion, createdAt);
    }
}
