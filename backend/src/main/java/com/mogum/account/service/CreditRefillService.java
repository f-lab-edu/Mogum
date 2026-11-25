package com.mogum.account.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.mogum.account.domain.CreditEntry;
import com.mogum.account.domain.UserStatus;
import com.mogum.account.repository.CreditEntryRepository;
import com.mogum.account.repository.UserRepository;
import com.mogum.common.logging.RequestLogFilter;
import com.mogum.common.time.SeoulClock;
import com.mogum.config.CreditProperties;

import lombok.extern.slf4j.Slf4j;

/** 대상 ID를 먼저 읽고, 사용자마다 독립 트랜잭션에서 채운다. */
@Service
@Slf4j
public class CreditRefillService {
	private final UserRepository userRepository;
	private final CreditEntryRepository creditEntryRepository;
	private final TransactionTemplate transactionTemplate;
	private final Clock clock;
	private final CreditProperties creditProperties;

	public CreditRefillService(UserRepository userRepository, CreditEntryRepository creditEntryRepository,
		PlatformTransactionManager platformTransactionManager, Clock clock, CreditProperties creditProperties) {
		this.userRepository = userRepository;
		this.creditEntryRepository = creditEntryRepository;
		this.transactionTemplate = new TransactionTemplate(platformTransactionManager);
		this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.clock = clock;
		this.creditProperties = creditProperties;
	}

	public void refillDue() {
		Instant now = clock.instant();
		List<Long> ids;
		try {
			ids = userRepository.findApprovedIdsDueForRefill(now);
		} catch (RuntimeException exception) {
			MDC.remove(RequestLogFilter.USER_ID);
			report(exception, true);
			return;
		}

		boolean failed = false;
		for (long id : ids) {
			try {
				MDC.put(RequestLogFilter.USER_ID, Long.toString(id));
				transactionTemplate.executeWithoutResult(status -> refillLocked(id, now));
			} catch (RuntimeException exception) {
				report(exception, !failed);
				failed = true;
			} finally {
				MDC.remove(RequestLogFilter.USER_ID);
			}
		}
	}

	private void refillLocked(long id, Instant now) {
		var found = userRepository.findByIdForUpdate(id);
		if (found.isEmpty()) {
			return;
		}
		var user = found.get();
		if (user.getStatus() != UserStatus.APPROVED || user.getCreditRefillAt().isAfter(now)) {
			return;
		}

		var next = user.getCreditRefillAt().atZone(SeoulClock.ZONE);
		do {
			next = next.plusDays(7);
		} while (!next.toInstant().isAfter(now));

		int amount = creditProperties.weeklyAmount() - user.getCreditBalance();
		user.refillCredits(creditProperties.weeklyAmount(), next.toInstant());
		creditEntryRepository.save(CreditEntry.refill(user, amount));
	}

	private static void report(RuntimeException exception, boolean stack) {
		if (stack) {
			// 원문을 담을 수 있는 message/cause/suppressed는 전달하지 않는다.
			var safe = new RuntimeException(exception.getClass().getName());
			safe.setStackTrace(exception.getStackTrace());
			log.error("credit refill failed", safe);
		} else {
			log.error("credit refill failed");
		}
	}
}
