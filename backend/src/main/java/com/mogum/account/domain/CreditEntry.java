package com.mogum.account.domain;

import com.mogum.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "credit_entries")
public class CreditEntry extends AuditedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private CreditEntryType type;
    @Column(nullable = false)
    private int amount;
    @Column(name = "job_id")
    private Long jobId;

    /** amount는 쓸 크기를 양수로 받으며, 기록에는 음수로 담는다. */
    public static CreditEntry consume(User user, long jobId, int amount) {
        return movement(user, jobId, CreditEntryType.CONSUME, amount, -1);
    }

    /** amount는 돌려줄 크기를 양수로 받으며, 기록에도 양수로 담는다. */
    public static CreditEntry restore(User user, long jobId, int amount) {
        return movement(user, jobId, CreditEntryType.RESTORE, amount, 1);
    }

    private static CreditEntry movement(User user, long jobId, CreditEntryType type, int amount, int sign) {
        if (amount <= 0) throw new IllegalArgumentException("amount must be positive");

        var entry = new CreditEntry();
        entry.user = user;
        entry.jobId = jobId;
        entry.type = type;
        entry.amount = sign * amount;
        return entry;
    }

    public static CreditEntry refill(User user, int amount) {
        var entry = new CreditEntry();
        entry.user = user;
        entry.type = CreditEntryType.REFILL;
        entry.amount = amount;
        return entry;
    }
}
