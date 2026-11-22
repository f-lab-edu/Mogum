package com.mogum.generation.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mogum.config.LlmProperties;
import com.mogum.generation.ChatCall;
import com.mogum.generation.ChatResult;
import com.mogum.generation.ChatSettings;
import com.mogum.generation.JobSnapshot;
import com.mogum.generation.call.LlmCallWriter;
import com.mogum.job.domain.JobType;
import com.mogum.support.IntegrationTest;

/** 기본 test에서 제외한다. llmTest를 사람이 실행할 때만 키를 읽어 한 번 유료 호출한다. */
@IntegrationTest
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".*\\S.*")
class OpenAiLiveIntegrationTest {
	@Autowired
	private LlmCallWriter llmCallWriter;
	@Autowired
	private LlmProperties llmProperties;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	@DisplayName("실제 짧은 요청 한 번의 끝남과 사용량 및 호출 기록을 확인한다")
	void completesOnePaidRequest() {
		// given
		var planning = llmProperties.chats().get("planning");
		var settings = new ChatSettings(planning.model(), planning.maxCompletionTokens(), planning.timeout(), 0);
		var job = new JobSnapshot(1, 1, JobType.NEW_COURSE, null, null, null, 1);
		try (var provider = new OpenAiChatProvider(System.getenv("OPENAI_API_KEY"), null, settings.timeout())) {
			var chat = new ChatCall(provider, llmCallWriter, llmProperties.prices());

			// when
			var result = chat.call(job, settings, "Reply briefly.", "Say OK.", Optional::of);

			// then
			assertThat(result).isInstanceOf(ChatResult.Success.class);
			var calls = jdbcTemplate.queryForList("SELECT * FROM llm_calls");
			assertThat(calls).hasSize(1);
			assertThat(calls.getFirst().get("outcome")).isEqualTo("SUCCEEDED");
			assertThat((Integer)calls.getFirst().get("input_tokens")).isPositive();
			assertThat((Integer)calls.getFirst().get("output_tokens")).isPositive();
			assertThat(calls.getFirst().get("cost")).isNotNull();
		}
	}
}
