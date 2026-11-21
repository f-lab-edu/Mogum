package com.mogum.generation.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mogum.generation.ChatCall;
import com.mogum.generation.ChatProvider;
import com.mogum.generation.ChatResult;
import com.mogum.generation.ChatSettings;
import com.mogum.generation.GenerationOutcome;
import com.mogum.generation.JobSnapshot;
import com.mogum.generation.TokenPrices;
import com.mogum.generation.call.LlmCallWriter;
import com.mogum.generation.call.domain.LlmCallOutcome;
import com.mogum.job.domain.JobType;

@ExtendWith(MockitoExtension.class)
class CoursePlanFormatChatTest {
	@Mock
	private ChatProvider chatProvider;
	@Mock
	private LlmCallWriter llmCallWriter;

	private final CoursePlanFormat coursePlanFormat = new CoursePlanFormat();
	private final JobSnapshot jobSnapshot = new JobSnapshot(1, 2, JobType.NEW_COURSE, null, null, null, 1);
	private final ChatSettings chatSettings = new ChatSettings("test-model", 4000, Duration.ofSeconds(1), 1);

	@Test
	@DisplayName("채팅 호출에 넘긴 해석 함수가 이어 붙인 코드펜스 답을 읽는다")
	void parsesContinuedChatReply() {
		// given
		String text = "앞 설명\n```json\n" + CoursePlanFormatTest.json("코스 제목", 1) + "\n```\n뒤 설명";
		int cut = text.indexOf("개념 정의") + 2;
		given(chatProvider.send(any()))
			.willReturn(new ChatProvider.Reply(text.substring(0, cut), LlmCallOutcome.TRUNCATED, null, null),
				new ChatProvider.Reply(text.substring(cut), LlmCallOutcome.SUCCEEDED, null, null));
		var prompt = coursePlanFormat.prompt(new CoursePlanInput.NewCourse(CoursePlanFormatTest.input()));

		// when
		var result = chat().call(jobSnapshot, chatSettings, prompt.system(), prompt.user(),
			coursePlanFormat::parseNewCourse);

		// then
		assertThat(result).isEqualTo(new ChatResult.Success<>(coursePlanFormat.parseNewCourse(text).orElseThrow()));
	}

	@Test
	@DisplayName("강의 수 형식 불합격은 채팅 호출의 INVALID_OUTPUT으로 이어진다")
	void returnsInvalidOutputThroughChatCall() {
		// given
		given(chatProvider.send(any())).willReturn(new ChatProvider.Reply(CoursePlanFormatTest.json("제목", 11),
			LlmCallOutcome.SUCCEEDED, null, null));
		var prompt = coursePlanFormat.prompt(new CoursePlanInput.NewCourse(CoursePlanFormatTest.input()));

		// when
		var result = chat().call(jobSnapshot, chatSettings, prompt.system(), prompt.user(),
			coursePlanFormat::parseNewCourse);

		// then
		assertThat(result).isEqualTo(new ChatResult.Failed<>(GenerationOutcome.Failure.INVALID_OUTPUT));
		then(llmCallWriter).should().finish(eq(0L), eq(LlmCallOutcome.INVALID_OUTPUT),
			eq(null), eq(null), eq(null), anyInt());
	}

	private ChatCall chat() {
		return new ChatCall(chatProvider, llmCallWriter,
			Map.of("test-model", new TokenPrices(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE)));
	}
}
