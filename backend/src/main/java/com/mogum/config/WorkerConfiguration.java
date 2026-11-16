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
    public WorkerLifecycle workerLifecycle(EntityManager em, PlatformTransactionManager transactions,
                                           CreditLedger ledger, Clock clock, WorkerProperties properties,
                                           List<JobGenerator> generators) {
        return new WorkerLifecycle(properties, scheduler ->
                new JobRunner(em, transactions, ledger, clock, properties, generators, scheduler));
    }
}
