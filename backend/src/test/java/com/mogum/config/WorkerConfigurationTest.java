package com.mogum.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

import com.mogum.account.service.CreditLedger;
import com.mogum.worker.WorkerLifecycle;

import jakarta.persistence.EntityManager;

class WorkerConfigurationTest {
	@Test
	@DisplayName("config는 생성 처리기가 없어도 수명 빈을 조립하고 수 0이면 실행하지 않는다")
	void wiresLifecycleWithoutGenerators() {
		// given
		var runner = new ApplicationContextRunner()
			.withInitializer(new ConfigDataApplicationContextInitializer())
			.withUserConfiguration(WorkerConfiguration.class, WorkerPropertiesTest.PropertiesConfiguration.class)
			.withBean(EntityManager.class, () -> mock(EntityManager.class))
			.withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
			.withBean(CreditLedger.class, () -> mock(CreditLedger.class))
			.withBean(Clock.class, Clock::systemUTC)
			.withPropertyValues("app.worker.count=0");

		// when
		runner.run(context -> {
			// then
			assertThat(context).hasNotFailed().hasSingleBean(WorkerLifecycle.class);
			assertThat(context.getBean(WorkerLifecycle.class).isRunning()).isFalse();
		});
	}
}
