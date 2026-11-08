package com.mogum.account.domain;

import com.mogum.common.persistence.AuditedEntity;
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
public class User extends AuditedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "text")
    private String provider;

    @Column(nullable = false, columnDefinition = "text")
    private String subject;

    @Column(nullable = false, columnDefinition = "text")
    private String email;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private UserStatus status;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "credit_balance", nullable = false)
    private int creditBalance;

    @Column(name = "credit_refill_at")
    private Instant creditRefillAt;

    public static User apply(String provider, String subject, String email, String name, int creditBalance) {
        var user = new User();
        user.provider = provider;
        user.subject = subject;
        user.email = email;
        user.name = name;
        user.status = UserStatus.APPLIED;
        user.creditBalance = creditBalance;
        return user;
    }
}
