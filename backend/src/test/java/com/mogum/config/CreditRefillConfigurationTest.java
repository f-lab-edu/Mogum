package com.mogum.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import com.mogum.account.service.CreditRefillService;

class CreditRefillConfigurationTest {
	private final CreditRefillService creditRefillService = mock(CreditRefillService.class);
	private final ApplicationContextRunner applicationContextRunner = new ApplicationContextRunner()
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withBean(CreditRefillService.class, () -> creditRefillService)
		.withUserConfiguration(CreditRefillConfiguration.class);

	@Test
	@DisplayName("기본 설정은 매일 서울 04시 작업을 등록하고 준비 완료 때 한 번 채운다")
	void registersDailyScheduleAndRefillsOnReady() {
		// when
		applicationContextRunner.run(context -> {
			// then
			assertThat(context).hasSingleBean(CreditRefillConfiguration.class);
			assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).hasSize(1);
			then(creditRefillService).shouldHaveNoInteractions();
			context.publishEvent(mock(ApplicationReadyEvent.class));
			then(creditRefillService).should().refillDue();
		});
	}

	@Test
	@DisplayName("끄면 스케줄과 기동 채움 모두 등록하지 않는다")
	void disablesBothTriggers() {
		// given
		var disabled = applicationContextRunner.withPropertyValues("app.credit.refill-enabled=false");

		// when
		disabled.run(context -> {
			// then
			assertThat(context).doesNotHaveBean(CreditRefillConfiguration.class);
			context.publishEvent(mock(ApplicationReadyEvent.class));
			then(creditRefillService).shouldHaveNoInteractions();
		});
	}

	@Test
	@DisplayName("cron과 시간대는 서버 기본 시간대와 무관하게 서울 매일 04시다")
	void declaresExplicitCronAndZone() throws Exception {
		// when
		var scheduled = CreditRefillConfiguration.class.getMethod("refillDue").getAnnotation(Scheduled.class);

		// then
		assertThat(scheduled.cron()).isEqualTo("0 0 4 * * *");
		assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
	}
}
