package com.mogum.generation.call;

import com.mogum.generation.call.domain.LlmCall;
import com.mogum.generation.call.domain.LlmCallOutcome;
import com.mogum.generation.call.domain.LlmCallType;
import com.mogum.generation.call.repository.LlmCallRepository;
import com.mogum.job.domain.JobType;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.SQLException;

/** 호출 전후의 짧은 쓰기만 커밋한다. 둘 사이의 유료 호출은 트랜잭션 밖에서 한다. */
public final class LlmCallWriter {
    private final EntityManager entityManager;
    private final LlmCallRepository llmCallRepository;
    private final TransactionTemplate transactionTemplate;

    public LlmCallWriter(
            EntityManager entityManager, LlmCallRepository llmCallRepository,
            PlatformTransactionManager platformTransactionManager
    ) {
        this.entityManager = entityManager;
        this.llmCallRepository = llmCallRepository;
        this.transactionTemplate = new TransactionTemplate(platformTransactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 호출을 보내기 전에 적는다. outcome과 duration은 아직 비어 있다. */
    public long start(Long jobId, Long userId, JobType jobType, LlmCallType callType, String model) {
        try {
            return insert(jobId, userId, jobType, callType, model);
        } catch (RuntimeException exception) {
            if (!isForeignKeyViolation(exception)) throw exception;
        }

        // 확인 직후 삭제된 연결은 새 트랜잭션에서 다시 확인해 비우고 한 번만 재시도한다.
        return insert(jobId, userId, jobType, callType, model);
    }

    /** 연결이 끊긴 호출도 결과를 채운다. 프로바이더가 주지 않은 사용량은 null이다. */
    public void finish(
            long callId, LlmCallOutcome outcome, Integer inputTokens, Integer outputTokens,
            BigDecimal cost, int durationMs
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            var call = llmCallRepository.findById(callId)
                    .orElseThrow(() -> new IllegalStateException("Call record is missing"));
            call.finish(outcome, inputTokens, outputTokens, cost, durationMs);
        });
    }

    private long insert(Long jobId, Long userId, JobType jobType, LlmCallType callType, String model) {
        return transactionTemplate.execute(status -> {
            Long existingJob = existingId("generation_jobs", jobId);
            Long existingUser = existingId("users", userId);
            return llmCallRepository.save(LlmCall.start(existingJob, existingUser, jobType, callType, model)).getId();
        });
    }

    private Long existingId(String table, Long id) {
        if (id == null) return null;

        var ids = entityManager.createNativeQuery("SELECT id FROM " + table + " WHERE id = :id")
                .setParameter("id", id)
                .getResultList();
        return ids.isEmpty() ? null : id;
    }

    private static boolean isForeignKeyViolation(Throwable exception) {
        for (var cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "23503".equals(sql.getSQLState())) return true;
        }
        return false;
    }
}
