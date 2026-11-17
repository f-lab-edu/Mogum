package com.mogum.generation.llm;

import com.mogum.generation.ChatProvider;
import com.mogum.generation.ChatSettings;
import com.mogum.generation.GenerationOutcome;
import com.mogum.generation.call.domain.LlmCallOutcome;
import com.mogum.job.domain.JobType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class OpenAiChatProviderTest {
    @ParameterizedTest(name = "헤더를 먼저 보냄: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("헤더 전이나 본문을 읽다가 시간 초과되어도 HTTP 요청을 자동 재시도하지 않는다")
    void timesOutOnceUsingRequestDeadline(boolean headersFirst, CapturedOutput output) throws Exception {
        // given
        try (var server = new FakeOpenAiServer();
                var provider = new OpenAiChatProvider("fake-key", server.baseUrl(), Duration.ofSeconds(5))) {
            var response = FakeOpenAiServer.completion("stop", "RESPONSE_PRIVATE_MARKER", true);
            if (headersFirst) server.respondWithDelayedBody(200, response, Duration.ofSeconds(2));
            else server.respond(200, response, Duration.ofSeconds(2));
            var settings = new ChatSettings("gpt-5.6-terra", 4000, Duration.ofMillis(300), 1);
            var request = new ChatProvider.Request(1L, JobType.NEW_COURSE, settings,
                    List.of(new ChatProvider.Message(ChatProvider.Role.USER, "PROMPT_PRIVATE_MARKER")));

            // when
            var result = provider.send(request);

            // then
            assertThat(result.outcome()).isEqualTo(LlmCallOutcome.TIMEOUT);
            assertThat(result.failure()).isEqualTo(GenerationOutcome.Failure.PROVIDER_UNAVAILABLE);
            assertThat(result.usage()).isNull();
            assertThat(server.requests()).hasSize(1);
            assertThat(output).doesNotContain("PROMPT_PRIVATE_MARKER", "RESPONSE_PRIVATE_MARKER", "fake-key");
        }
    }

    @Test
    @DisplayName("연결이 끊어져도 예외나 원문을 내보내지 않고 한 번만 요청한다")
    void handlesDisconnectedResponse(CapturedOutput output) throws Exception {
        // given
        try (var server = new FakeOpenAiServer();
                var provider = new OpenAiChatProvider("fake-key", server.baseUrl(), Duration.ofSeconds(2))) {
            var request = new ChatProvider.Request(1L, JobType.NEW_COURSE, new ChatSettings("gpt-5.6-terra", 4000,
                    Duration.ofSeconds(2), 1),
                    List.of(new ChatProvider.Message(ChatProvider.Role.USER, "PROMPT_PRIVATE_MARKER")));

            // when
            var result = provider.send(request);

            // then
            assertThat(result.outcome()).isEqualTo(LlmCallOutcome.PROVIDER_ERROR);
            assertThat(result.failure()).isEqualTo(GenerationOutcome.Failure.PROVIDER_UNAVAILABLE);
            assertThat(server.requests()).hasSize(1);
            assertThat(output).doesNotContain("PROMPT_PRIVATE_MARKER", "fake-key");
        }
    }

    @Test
    @DisplayName("잘못된 오류 code도 HTTP 상태로 분류하고 예외를 내보내지 않는다")
    void containsMalformedErrorCode(CapturedOutput output) throws Exception {
        // given
        try (var server = new FakeOpenAiServer();
                var provider = new OpenAiChatProvider("fake-key", server.baseUrl(), Duration.ofSeconds(2))) {
            server.respond(401, """
                    {"error":{"message":"RESPONSE_PRIVATE_MARKER","type":"invalid_request_error","code":123}}
                    """);
            var request = new ChatProvider.Request(1L, JobType.NEW_COURSE, new ChatSettings("gpt-5.6-terra", 4000,
                    Duration.ofSeconds(2), 1),
                    List.of(new ChatProvider.Message(ChatProvider.Role.USER, "PROMPT_PRIVATE_MARKER")));

            // when
            var result = provider.send(request);

            // then
            assertThat(result.outcome()).isEqualTo(LlmCallOutcome.REJECTED);
            assertThat(result.failure()).isEqualTo(GenerationOutcome.Failure.PROVIDER_REJECTED);
            assertThat(server.requests()).hasSize(1);
            assertThat(output).doesNotContain("PROMPT_PRIVATE_MARKER", "RESPONSE_PRIVATE_MARKER", "fake-key");
        }
    }

    @Test
    @DisplayName("응답 JSON이 깨져도 형식 실패로 바꾸고 원문을 남기지 않는다")
    void containsMalformedJson(CapturedOutput output) throws Exception {
        // given
        try (var server = new FakeOpenAiServer();
                var provider = new OpenAiChatProvider("fake-key", server.baseUrl(), Duration.ofSeconds(2))) {
            server.respond(200, "RESPONSE_PRIVATE_MARKER");
            var request = new ChatProvider.Request(1L, JobType.NEW_COURSE, new ChatSettings("gpt-5.6-terra", 4000,
                    Duration.ofSeconds(2), 1),
                    List.of(new ChatProvider.Message(ChatProvider.Role.USER, "PROMPT_PRIVATE_MARKER")));

            // when
            var result = provider.send(request);

            // then
            assertThat(result.outcome()).isEqualTo(LlmCallOutcome.INVALID_OUTPUT);
            assertThat(result.failure()).isEqualTo(GenerationOutcome.Failure.INVALID_OUTPUT);
            assertThat(server.requests()).hasSize(1);
            assertThat(output).doesNotContain("PROMPT_PRIVATE_MARKER", "RESPONSE_PRIVATE_MARKER", "fake-key");
        }
    }
}
