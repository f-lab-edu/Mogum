package com.mogum.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.FakeCreditEntryRepository;
import com.mogum.account.repository.UserRepository;
import com.mogum.common.logging.RequestLogFilter;
import com.mogum.config.CreditProperties;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class CreditRefillServiceTest {
	@Mock
	UserRepository userRepository;
	@Mock
	PlatformTransactionManager platformTransactionManager;
	@Mock
	TransactionStatus transactionStatus;
	@Mock
	Clock clock;
	private final FakeCreditEntryRepository creditEntryRepository = new FakeCreditEntryRepository();
	private final Instant now = Instant.parse("2026-11-07T19:00:00Z");
	private final CreditProperties creditProperties = new CreditProperties(25, 1, 1, 1, 1, 1, 1);

	@Test
	@DisplayName("목록 읽기 실패는 사용자 없이 안전한 스택 한 번만 남기고 밖으로 던지지 않는다")
	void containsCandidateQueryFailure(CapturedOutput output) {
		// given
		given(clock.instant()).willReturn(now);
		given(userRepository.findApprovedIdsDueForRefill(now)).willThrow(
			new IllegalStateException("query-private-marker", new RuntimeException("cause-marker")));
		var service = service();

		// when & then
		assertThatCode(service::refillDue).doesNotThrowAnyException();
		assertThat(output.getAll()).contains("credit refill failed", "java.lang.IllegalStateException")
			.doesNotContain("query-private-marker", "cause-marker", "userId=");
		assertThat(output.getAll().lines().filter(line -> line.contains("credit refill failed"))).hasSize(1);
		assertThat(MDC.get(RequestLogFilter.USER_ID)).isNull();
		then(platformTransactionManager).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("시각은 한 번 읽고 목록 조회 뒤 사람마다 독립 트랜잭션을 시작하고 끝낸다")
	void readsClockOnceAndStartsSeparateTransactionsAfterSelection() {
		// given
		given(clock.instant()).willReturn(now);
		given(userRepository.findApprovedIdsDueForRefill(now)).willReturn(List.of(1L, 2L));
		given(platformTransactionManager.getTransaction(any())).willAnswer(invocation -> {
			TransactionDefinition definition = invocation.getArgument(0);
			assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
			return transactionStatus;
		});
		for (long id : List.of(1L, 2L)) {
			given(userRepository.findByIdForUpdate(id)).willReturn(java.util.Optional.of(
				UserFixtures.withId(UserFixtures.approved(id + "@example.com", 7, now), id)));
		}

		// when
		service().refillDue();

		// then
		var order = inOrder(clock, userRepository, platformTransactionManager);
		order.verify(clock).instant();
		order.verify(userRepository).findApprovedIdsDueForRefill(now);
		for (long id : List.of(1L, 2L)) {
			order.verify(platformTransactionManager).getTransaction(any());
			order.verify(userRepository).findByIdForUpdate(id);
			order.verify(platformTransactionManager).commit(transactionStatus);
		}
		order.verifyNoMoreInteractions();
		then(clock).should().instant();
		assertThat(creditEntryRepository.entries()).hasSize(2);
	}

	private CreditRefillService service() {
		return new CreditRefillService(userRepository, creditEntryRepository, platformTransactionManager,
			clock, creditProperties);
	}
}
