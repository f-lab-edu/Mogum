package com.mogum.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import com.mogum.account.service.CreditRefillService;

import lombok.RequiredArgsConstructor;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.credit.refill-enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
@RequiredArgsConstructor
public class CreditRefillConfiguration {
	private final CreditRefillService creditRefillService;

	@Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
	@EventListener(ApplicationReadyEvent.class)
	public void refillDue() {
		creditRefillService.refillDue();
	}
}
