package com.mogum.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mogum.generation.call.LlmCallWriter;
import com.mogum.generation.call.domain.LlmCallOutcome;
import com.mogum.generation.call.domain.LlmCallType;
import com.mogum.job.domain.JobType;

@ExtendWith(MockitoExtension.class)
class ChatCallTest {
	@Mock
	private ChatProvider chatProvider;
	@Mock
	private LlmCallWriter llmCallWriter;

	private final JobSnapshot jobSnapshot = new JobSnapshot(1, 2, JobType.NEW_COURSE, null, null, null, 1);
	private final ChatSettings chatSettings = new ChatSettings("gpt-5.6-terra", 4000, Duration.ofSeconds(50), 1);
	private final Map<String, TokenPrices> prices = Map.of("gpt-5.6-terra",
		new TokenPrices(new BigDecimal("2"), new BigDecimal("0.2"), new BigDecimal("12")));

	@Test
	@DisplayName("호출 기록을 먼저 시작하고 프로바이더가 끝난 뒤 마친다")
	void writesAroundProviderCall() {
		// given
		given(llmCallWriter.start(1L, 2L, JobType.NEW_COURSE, LlmCallType.CHAT, "gpt-5.6-terra"))
			.willReturn(10L);
		given(chatProvider.send(any())).willReturn(new ChatProvider.Reply("완성", LlmCallOutcome.SUCCEEDED,
			null, new ChatProvider.Usage(100, 40, 20)));
		var chat = new ChatCall(chatProvider, llmCallWriter, prices);

		// when
		var result = chat.call(jobSnapshot, chatSettings, "system", "user", Optional::of);

		// then
		assertThat(result).isEqualTo(new ChatResult.Success<>("완성"));
		var order = inOrder(llmCallWriter, chatProvider);
		order.verify(llmCallWriter).start(1L, 2L, JobType.NEW_COURSE, LlmCallType.CHAT, "gpt-5.6-terra");
		order.verify(chatProvider).send(argThat(request -> request.jobId() == jobSnapshot.id()
			&& request.jobType() == jobSnapshot.type()));
		order.verify(llmCallWriter).finish(eq(10L), eq(LlmCallOutcome.SUCCEEDED), eq(100), eq(20),
			argThat(cost -> cost.compareTo(new BigDecimal("0.000368")) == 0), anyInt());
	}

	@Test
	@DisplayName("시작 기록을 쓰지 못하면 유료 호출을 보내지 않고 실패한다")
	void avoidsPaidCallWhenStartFails() {
		// given
		given(llmCallWriter.start(1L, 2L, JobType.NEW_COURSE, LlmCallType.CHAT, "gpt-5.6-terra"))
			.willThrow(new IllegalStateException("private marker"));
		var chat = new ChatCall(chatProvider, llmCallWriter, prices);

		// when
		var result = chat.call(jobSnapshot, chatSettings, "system", "user", Optional::of);

		// then
		assertThat(result).isEqualTo(new ChatResult.Failed<>(GenerationOutcome.Failure.PROVIDER_UNAVAILABLE));
		then(chatProvider).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("종료 기록 실패는 이미 받은 결과의 저장을 막지 않는다")
	void preservesResultWhenFinishFails() {
		// given
		given(llmCallWriter.start(1L, 2L, JobType.NEW_COURSE, LlmCallType.CHAT, "gpt-5.6-terra"))
			.willReturn(10L);
		given(chatProvider.send(any())).willReturn(new ChatProvider.Reply("완성", LlmCallOutcome.SUCCEEDED,
			null, null));
		willThrow(new IllegalStateException("private marker")).given(llmCallWriter)
			.finish(eq(10L), eq(LlmCallOutcome.SUCCEEDED), eq(null), eq(null), eq(null), anyInt());
		var chat = new ChatCall(chatProvider, llmCallWriter, prices);

		// when
		var result = chat.call(jobSnapshot, chatSettings, "system", "user", Optional::of);

		// then
		assertThat(result).isEqualTo(new ChatResult.Success<>("완성"));
	}

	@Test
	@DisplayName("호출자가 트랜잭션을 쥐면 기록이나 유료 요청을 보내지 않는다")
	void refusesAmbientTransaction() {
		// given
		var chat = new ChatCall(chatProvider, llmCallWriter, prices);
		org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
		try {
			// when
			var result = chat.call(jobSnapshot, chatSettings, "system", "user", Optional::of);

			// then
			assertThat(result).isEqualTo(new ChatResult.Failed<>(GenerationOutcome.Failure.PROVIDER_UNAVAILABLE));
			then(chatProvider).shouldHaveNoInteractions();
			then(llmCallWriter).shouldHaveNoInteractions();
		} finally {
			org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
		}
	}

	@Test
	@DisplayName("어댑터가 예외를 내도 기록을 마치고 작업 실패로 돌려준다")
	void containsUnexpectedProviderException() {
		// given
		given(llmCallWriter.start(1L, 2L, JobType.NEW_COURSE, LlmCallType.CHAT, "gpt-5.6-terra"))
			.willReturn(10L);
		given(chatProvider.send(any())).willThrow(new IllegalArgumentException("private marker"));
		var chat = new ChatCall(chatProvider, llmCallWriter, prices);

		// when
		var result = chat.call(jobSnapshot, chatSettings, "system", "user", Optional::of);

		// then
		assertThat(result).isEqualTo(new ChatResult.Failed<>(GenerationOutcome.Failure.PROVIDER_UNAVAILABLE));
		then(llmCallWriter).should().finish(eq(10L), eq(LlmCallOutcome.PROVIDER_ERROR),
			eq(null), eq(null), eq(null), anyInt());
	}

	@Test
	@DisplayName("해석 함수가 null을 내면 사용량을 보존하고 마지막 호출을 형식 실패로 마친다")
	void recordsNullParse() {
		// given
		given(llmCallWriter.start(1L, 2L, JobType.NEW_COURSE, LlmCallType.CHAT, "gpt-5.6-terra"))
			.willReturn(10L);
		given(chatProvider.send(any())).willReturn(new ChatProvider.Reply("완성", LlmCallOutcome.SUCCEEDED,
			null, new ChatProvider.Usage(100, 40, 20)));
		var chat = new ChatCall(chatProvider, llmCallWriter, prices);

		// when
		var result = chat.call(jobSnapshot, chatSettings, "system", "user", text -> null);

		// then
		assertThat(result).isEqualTo(new ChatResult.Failed<>(GenerationOutcome.Failure.INVALID_OUTPUT));
		then(llmCallWriter).should().finish(eq(10L), eq(LlmCallOutcome.INVALID_OUTPUT), eq(100), eq(20),
			argThat(cost -> cost.compareTo(new BigDecimal("0.000368")) == 0), anyInt());
		then(chatProvider).should().send(any());
		then(chatProvider).shouldHaveNoMoreInteractions();
	}

	@Test
	@DisplayName("해석 함수가 빈 값을 내면 마지막 호출을 형식 실패로 마친다")
	void recordsEmptyParse() {
		// given
		given(llmCallWriter.start(1L, 2L, JobType.NEW_COURSE, LlmCallType.CHAT, "gpt-5.6-terra"))
			.willReturn(10L);
		given(chatProvider.send(any())).willReturn(new ChatProvider.Reply("완성", LlmCallOutcome.SUCCEEDED,
			null, null));
		var chat = new ChatCall(chatProvider, llmCallWriter, prices);

		// when
		var result = chat.call(jobSnapshot, chatSettings, "system", "user", text -> Optional.empty());

		// then
		assertThat(result).isEqualTo(new ChatResult.Failed<>(GenerationOutcome.Failure.INVALID_OUTPUT));
		then(llmCallWriter).should().finish(eq(10L), eq(LlmCallOutcome.INVALID_OUTPUT),
			eq(null), eq(null), eq(null), anyInt());
	}
}
