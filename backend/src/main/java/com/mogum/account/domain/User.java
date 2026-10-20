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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
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

    // 첫 로그인으로 가입한 회원 — ACTIVE 로 시작한다. 역할은 바깥 정보(관리자 목록)라 서비스가 정해 넘긴다
    public static User join(String provider, String subject, String email, UserRole role, Instant joinedAt) {
        var user = new User();
        user.provider = provider;
        user.subject = subject;
        user.email = email;
        user.status = UserStatus.ACTIVE;
        user.role = role;
        user.createdAt = joinedAt;
        return user;
    }
}
