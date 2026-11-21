package com.mogum.generation;

import com.mogum.config.LlmProperties;
import com.mogum.generation.call.LlmCallWriter;
import com.mogum.generation.llm.FakeOpenAiServer;
import com.mogum.generation.llm.OpenAiChatProvider;
import com.mogum.job.domain.JobType;
import com.mogum.support.IntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class ChatCallIntegrationTest {
    @Autowired private LlmCallWriter llmCallWriter;
    @Autowired private LlmProperties llmProperties;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private DataSource dataSource;

    private final JobSnapshot jobSnapshot = new JobSnapshot(1, 1, JobType.NEW_COURSE, null, null, null, 1);

    @ParameterizedTest(name = "{0} {1} → {2} {3}")
    @CsvSource({"500,server_error,PROVIDER_ERROR,PROVIDER_UNAVAILABLE",
            "503,server_error,PROVIDER_ERROR,PROVIDER_UNAVAILABLE",
            "429,rate_limit_exceeded,RATE_LIMITED,PROVIDER_UNAVAILABLE",
            "429,insufficient_quota,REJECTED,PROVIDER_REJECTED",
            "401,invalid_api_key,REJECTED,PROVIDER_REJECTED",
            "403,permission_denied,REJECTED,PROVIDER_REJECTED",
            "404,model_not_found,REJECTED,PROVIDER_REJECTED",
            "422,unprocessable,REJECTED,PROVIDER_REJECTED",
            "400,context_length_exceeded,REJECTED,INPUT_TOO_LARGE",
            "400,other_error,REJECTED,PROVIDER_REJECTED"})
    @DisplayName("HTTP 실패는 호출과 작업 갈래로 나누고 한 번만 요청하며 원문을 남기지 않는다")
    void classifiesHttpFailure(int status, String code, String outcome, String failure, CapturedOutput output)
            throws Exception {
        // given
        try (var server = new FakeOpenAiServer(); var provider = provider(server)) {
            server.respond(status, FakeOpenAiServer.error(code));
            var chat = chat(provider);

            // when
            var result = call(chat, Optional::of);

            // then
            assertThat(result).isEqualTo(new ChatResult.Failed<>(GenerationOutcome.Failure.valueOf(failure)));
            assertRequest(server, 1);
            assertCall(0, "CHAT", outcome, false);
            assertThat(output).doesNotContain("PROMPT_PRIVATE_MARKER", "RESPONSE_PRIVATE_MARKER", "fake-key");
        }
    }

    @ParameterizedTest(name = "{0} → {2}")
    @CsvSource({"stop,RESPONSE_PRIVATE_MARKER,SUCCEEDED", "content_filter,RESPONSE_PRIVATE_MARKER,INVALID_OUTPUT",
            "tool_calls,RESPONSE_PRIVATE_MARKER,INVALID_OUTPUT", "future_reason,RESPONSE_PRIVATE_MARKER,INVALID_OUTPUT",
            "stop,'',INVALID_OUTPUT", "stop,'   ',INVALID_OUTPUT"})
    @DisplayName("끝남만 해석하고 다른 종료나 빈 내용은 형식 실패로 남긴다")
    void validatesCompletion(String reason, String text, String outcome, CapturedOutput output) throws Exception {
        // given
        try (var server = new FakeOpenAiServer(); var provider = provider(server)) {
            server.respond(200, FakeOpenAiServer.completion(reason, text, true));

            // when
            var result = call(chat(provider), Optional::of);

            // then
            if (outcome.equals("SUCCEEDED")) {
                assertThat(result).isEqualTo(new ChatResult.Success<>(text));
            } else {
                assertThat(result).isEqualTo(new ChatResult.Failed<>(GenerationOutcome.Failure.INVALID_OUTPUT));
            }
            assertRequest(server, 1);
            assertCall(0, "CHAT", outcome, true);
            assertThat(output).doesNotContain("PROMPT_PRIVATE_MARKER", "RESPONSE_PRIVATE_MARKER", "fake-key");
        }
    }

    @Test
    @DisplayName("이어 쓰기는 앞부분과 지시를 대화에 넣고 붙인 값만 해석한다")
    void continuesAndParsesCombinedText() throws Exception {
        // given
        try (var server = new FakeOpenAiServer(); var provider = provider(server)) {
            server.respond(200, FakeOpenAiServer.completion("length", "앞", true));
            server.respond(200, FakeOpenAiServer.completion("stop", "뒤", true));

            // when
            var result = call(chat(provider), text -> text.equals("앞뒤") ? Optional.of(42) : Optional.empty());

            // then
            assertThat(result).isEqualTo(new ChatResult.Success<>(42));
            assertRequest(server, 2);
            var messages = server.requests().get(1).body().get("messages");
            assertThat(messages).hasSize(4);
            assertThat(messages.get(2).get("role").asString()).isEqualTo("assistant");
            assertThat(messages.get(2).get("content").asString()).isEqualTo("앞");
            assertThat(messages.get(3).get("role").asString()).isEqualTo("user");
            assertThat(messages.get(3).get("content").asString())
                    .contains("반복하지 말고", "바로 다음 글자부터", "머리말", "코드펜스");
            assertCall(0, "CHAT", "TRUNCATED", true);
            assertCall(1, "CONTINUATION", "SUCCEEDED", true);
        }
    }

    @Test
    @DisplayName("이어 써도 잘리면 호출 한도를 지키고 형식 실패를 돌려준다")
    void stopsAfterContinuationLimit() throws Exception {
        // given
        try (var server = new FakeOpenAiServer(); var provider = provider(server)) {
            server.respond(200, FakeOpenAiServer.completion("length", "앞", true));
            server.respond(200, FakeOpenAiServer.completion("length", "뒤", true));

            // when
            var result = call(chat(provider), Optional::of);

            // then
            assertThat(result).isEqualTo(new ChatResult.Failed<>(GenerationOutcome.Failure.INVALID_OUTPUT));
            assertRequest(server, 2);
            assertCall(0, "CHAT", "TRUNCATED", true);
            assertCall(1, "CONTINUATION", "TRUNCATED", true);
        }
    }

    @Test
    @DisplayName("이어 붙인 텍스트의 해석이 실패하면 마지막 호출에 형식 실패를 남긴다")
    void recordsInvalidFinalParse(CapturedOutput output) throws Exception {
        // given
        try (var server = new FakeOpenAiServer(); var provider = provider(server)) {
            server.respond(200, FakeOpenAiServer.completion("length", "앞", true));
            server.respond(200, FakeOpenAiServer.completion("stop", "RESPONSE_PRIVATE_MARKER", true));

            // when
            var result = call(chat(provider), text -> {
                throw new IllegalArgumentException(text + "PROMPT_PRIVATE_MARKER");
            });

            // then
            assertThat(result).isEqualTo(new ChatResult.Failed<>(GenerationOutcome.Failure.INVALID_OUTPUT));
            assertCall(0, "CHAT", "TRUNCATED", true);
            assertCall(1, "CONTINUATION", "INVALID_OUTPUT", true);
            assertThat(output).doesNotContain("PROMPT_PRIVATE_MARKER", "RESPONSE_PRIVATE_MARKER");
        }
    }

    @Test
    @DisplayName("사용량을 주지 않아도 성공하고 토큰과 비용은 비운다")
    void preservesMissingUsage() throws Exception {
        // given
        try (var server = new FakeOpenAiServer(); var provider = provider(server)) {
            server.respond(200, FakeOpenAiServer.completion("stop", "완성", false));

            // when
            var result = call(chat(provider), Optional::of);

            // then
            assertThat(result).isEqualTo(new ChatResult.Success<>("완성"));
            assertCall(0, "CHAT", "SUCCEEDED", false);
        }
    }

    @Test
    @DisplayName("시간 초과도 기록을 마치고 요청은 한 번만 보낸다")
    void recordsTimeout() throws Exception {
        // given
        try (var server = new FakeOpenAiServer(); var provider = provider(server)) {
            server.respond(200, FakeOpenAiServer.completion("stop", "완성", true), Duration.ofSeconds(2));
            var settings = new ChatSettings("gpt-5.6-terra", 4000, Duration.ofMillis(300), 1);

            // when
            var result = chat(provider).call(jobSnapshot, settings, "system", "PROMPT_PRIVATE_MARKER", Optional::of);

            // then
            assertThat(result).isEqualTo(new ChatResult.Failed<>(GenerationOutcome.Failure.PROVIDER_UNAVAILABLE));
            assertRequest(server, 1);
            assertCall(0, "CHAT", "TIMEOUT", false);
        }
    }

    @Test
    @DisplayName("연결 오류도 호출 기록을 마치고 예외 없이 실패한다")
    void recordsConnectionFailure() throws Exception {
        // given
        try (var server = new FakeOpenAiServer(); var provider = provider(server)) {
            // 응답을 넣지 않아 서버가 연결을 닫게 한다.

            // when
            var result = call(chat(provider), Optional::of);

            // then
            assertThat(result).isEqualTo(new ChatResult.Failed<>(GenerationOutcome.Failure.PROVIDER_UNAVAILABLE));
            assertRequest(server, 1);
            assertCall(0, "CHAT", "PROVIDER_ERROR", false);
        }
    }

    @Test
    @DisplayName("해석이 실패해도 실제 사용량을 호출 기록에 남긴다")
    void recordsFailedParseWithUsage() throws Exception {
        // given
        try (var server = new FakeOpenAiServer(); var provider = provider(server)) {
            server.respond(200, FakeOpenAiServer.completion("stop", "형식 아님", true));

            // when
            var result = call(chat(provider), text -> Optional.empty());

            // then
            assertThat(result).isEqualTo(new ChatResult.Failed<>(GenerationOutcome.Failure.INVALID_OUTPUT));
            assertRequest(server, 1);
            assertCall(0, "CHAT", "INVALID_OUTPUT", true);
        }
    }

    private OpenAiChatProvider provider(FakeOpenAiServer server) {
        return new OpenAiChatProvider("fake-key", server.baseUrl(), Duration.ofSeconds(3));
    }

    private ChatCall chat(ChatProvider provider) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        ChatProvider observed = request -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.hasResource(entityManagerFactory)).isFalse();
            assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
            assertThat(activeConnections()).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM llm_calls WHERE outcome IS NULL", Integer.class)).isEqualTo(1);
            return provider.send(request);
        };
        return new ChatCall(observed, llmCallWriter, llmProperties.prices());
    }

    private int activeConnections() {
        try {
            return dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class)
                    .getHikariPoolMXBean().getActiveConnections();
        } catch (java.sql.SQLException exception) {
            throw new AssertionError(exception);
        }
    }

    private <T> ChatResult<T> call(ChatCall chat, Function<String, Optional<T>> parser) {
        return chat.call(jobSnapshot, llmProperties.chats().get("planning"),
                "system", "PROMPT_PRIVATE_MARKER", parser);
    }

    private void assertRequest(FakeOpenAiServer server, int count) {
        assertThat(server.requests()).hasSize(count);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM llm_calls", Integer.class)).isEqualTo(count);
        for (var request : server.requests()) {
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/v1/chat/completions");
            assertThat(request.authorization()).isEqualTo("Bearer fake-key");
            assertThat(request.body().get("model").asString()).isEqualTo("gpt-5.6-terra");
            assertThat(request.body().get("max_completion_tokens").asInt()).isEqualTo(4000);
            assertThat(request.body().has("max_tokens")).isFalse();
            assertThat(request.body().has("tools")).isFalse();
            assertThat(request.body().get("reasoning_effort").asString()).isEqualTo("none");
            var messages = request.body().get("messages");
            assertThat(messages.get(0).get("role").asString()).isEqualTo("system");
            assertThat(messages.get(1).get("role").asString()).isEqualTo("user");
            assertThat(messages.get(1).get("content").asString()).isEqualTo("PROMPT_PRIVATE_MARKER");
        }
    }

    private void assertCall(int index, String type, String outcome, boolean usage) {
        var rows = jdbcTemplate.queryForList("SELECT * FROM llm_calls ORDER BY id");
        var row = rows.get(index);
        assertThat(row.get("model")).isEqualTo("gpt-5.6-terra");
        assertThat(row.get("job_type")).isEqualTo("NEW_COURSE");
        assertThat(row.get("call_type")).isEqualTo(type);
        assertThat(row.get("outcome")).isEqualTo(outcome);
        assertThat((Integer) row.get("duration_ms")).isGreaterThanOrEqualTo(0);
        assertThat(row.get("input_tokens")).isEqualTo(usage ? 100 : null);
        assertThat(row.get("output_tokens")).isEqualTo(usage ? 20 : null);
        if (usage) {
            assertThat((java.math.BigDecimal) row.get("cost")).isEqualByComparingTo("0.000368");
        } else {
            assertThat(row.get("cost")).isNull();
        }
    }
}
