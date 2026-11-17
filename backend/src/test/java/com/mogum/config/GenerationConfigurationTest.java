package com.mogum.config;

import com.mogum.generation.call.LlmCallWriter;
import com.mogum.generation.call.repository.LlmCallRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GenerationConfigurationTest {
    @Test
    @DisplayName("config는 호출 기록 쓰기 빈을 조립한다")
    void wiresCallWriter() {
        // given
        var runner = new ApplicationContextRunner()
                .withUserConfiguration(GenerationConfiguration.class)
                .withBean(EntityManager.class, () -> mock(EntityManager.class))
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean(LlmCallRepository.class, () -> mock(LlmCallRepository.class));

        // when
        runner.run(context -> {
            // then
            assertThat(context).hasNotFailed().hasSingleBean(LlmCallWriter.class);
        });
    }
}
