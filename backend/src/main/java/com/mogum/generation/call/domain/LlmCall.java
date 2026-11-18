package com.mogum.generation.call.domain;

import com.mogum.common.persistence.AuditedEntity;
import com.mogum.job.domain.JobType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Objects;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "llm_calls")
public class LlmCall extends AuditedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id")
    private Long jobId;

    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, columnDefinition = "text")
    private JobType jobType;

    @Enumerated(EnumType.STRING)
    @Column(name = "call_type", nullable = false, columnDefinition = "text")
    private LlmCallType callType;

    @Column(nullable = false, columnDefinition = "text")
    private String model;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(columnDefinition = "numeric")
    private BigDecimal cost;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "text")
    private LlmCallOutcome outcome;

    public static LlmCall start(Long jobId, Long userId, JobType jobType, LlmCallType callType, String model) {
        var call = new LlmCall();
        call.jobId = jobId;
        call.userId = userId;
        call.jobType = jobType;
        call.callType = callType;
        call.model = model;
        return call;
    }

    public void finish(
            LlmCallOutcome outcome, Integer inputTokens, Integer outputTokens,
            BigDecimal cost, int durationMs
    ) {
        this.outcome = Objects.requireNonNull(outcome);
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.cost = cost;
        this.durationMs = durationMs;
    }
}
