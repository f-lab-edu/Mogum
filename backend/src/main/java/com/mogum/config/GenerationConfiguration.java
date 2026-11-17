package com.mogum.config;

import com.mogum.generation.call.LlmCallWriter;
import com.mogum.generation.call.repository.LlmCallRepository;
import jakarta.persistence.EntityManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class GenerationConfiguration {
    @Bean
    public LlmCallWriter llmCallWriter(EntityManager entityManager, LlmCallRepository llmCallRepository,
            PlatformTransactionManager platformTransactionManager) {
        return new LlmCallWriter(entityManager, llmCallRepository, platformTransactionManager);
    }
}
