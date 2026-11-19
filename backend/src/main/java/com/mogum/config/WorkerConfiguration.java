package com.mogum.config;

import com.mogum.account.service.CreditLedger;
import com.mogum.generation.JobGenerator;
import com.mogum.worker.JobRunner;
import com.mogum.worker.WorkerLifecycle;
import jakarta.persistence.EntityManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.util.List;

@Configuration(proxyBeanMethods = false)
public class WorkerConfiguration {
    @Bean
    public WorkerLifecycle workerLifecycle(
            EntityManager entityManager,
            PlatformTransactionManager platformTransactionManager,
            CreditLedger creditLedger, Clock clock, WorkerProperties workerProperties,
            List<JobGenerator> generators
    ) {
        return new WorkerLifecycle(workerProperties, scheduler ->
                new JobRunner(entityManager, platformTransactionManager, creditLedger, clock, workerProperties,
                        generators, scheduler));
    }
}
