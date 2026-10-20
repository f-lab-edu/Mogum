package com.mogum.account.domain;

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
public class User {
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

    protected User() {
    }

    public User(ExternalIdentity identity, String email, UserStatus status, UserRole role, Instant createdAt) {
        this.provider = identity.provider();
        this.subject = identity.subject();
        this.email = email;
        this.status = status;
        this.role = role;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public UserStatus getStatus() {
        return status;
    }

    public UserRole getRole() {
        return role;
    }

    public Instant getTermsAgreedAt() {
        return termsAgreedAt;
    }

    public String getTermsVersion() {
        return termsVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
