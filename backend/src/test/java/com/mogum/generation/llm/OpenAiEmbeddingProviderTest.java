package com.mogum.generation.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.mogum.generation.EmbeddingProvider;
import com.mogum.generation.EmbeddingSettings;
import com.mogum.generation.GenerationOutcome;
import com.mogum.generation.call.domain.LlmCallOutcome;
import com.mogum.job.domain.JobType;

@ExtendWith(OutputCaptureExtension.class)
class OpenAiEmbeddingProviderTest {
	@Test
	@DisplayName("정의 배열을 한 번 보내고 섞인 index를 입력 순서로 맞추며 사용량을 받는다")
	void sendsBatchAndOrdersVectors(CapturedOutput output) throws Exception {
		// given
		try (var server = new FakeOpenAiServer();
			var client = new OpenAiChatProvider("fake-key", server.baseUrl(), Duration.ofSeconds(5))) {
			server.respond(200, FakeOpenAiServer.embeddings(
				"{\"index\":1,\"embedding\":[3.0,4.0]}, {\"index\":0,\"embedding\":[1.0,2.0]}", true));
			var provider = new OpenAiEmbeddingProvider(client);

			// when
			var result = provider.send(request(Duration.ofSeconds(2)));

			// then
			assertThat(result.outcome()).isEqualTo(LlmCallOutcome.SUCCEEDED);
			assertThat(result.vectors()).containsExactly(List.of(1f, 2f), List.of(3f, 4f));
			assertThat(result.inputTokens()).isEqualTo(100);
			assertThat(result.failure()).isNull();
			assertThat(server.requests()).hasSize(1);
			var sent = server.requests().getFirst();
			assertThat(sent.method()).isEqualTo("POST");
			assertThat(sent.path()).isEqualTo("/v1/embeddings");
			assertThat(sent.authorization()).isEqualTo("Bearer fake-key");
			assertThat(sent.body().get("model").asString()).isEqualTo("text-embedding-3-large");
			assertThat(sent.body().get("encoding_format").asString()).isEqualTo("base64");
			var input = sent.body().get("input");
			assertThat(input.isArray()).isTrue();
			assertThat(input).hasSize(2);
			assertThat(input.get(0).asString()).isEqualTo("DEFINITION_PRIVATE_MARKER");
			assertThat(input.get(1).asString()).isEqualTo("두 번째 정의");
			assertThat(output).doesNotContain("DEFINITION_PRIVATE_MARKER", "fake-key");
		}
	}

	@Test
	@DisplayName("base64 벡터는 SDK가 float 목록으로 풀고 사용량 누락은 비워 둔다")
	void decodesBase64WithoutUsage() throws Exception {
		// given
		try (var server = new FakeOpenAiServer();
			var client = new OpenAiChatProvider("fake-key", server.baseUrl(), Duration.ofSeconds(5))) {
			server.respond(200, FakeOpenAiServer.embeddings(
				"{\"index\":0,\"embedding\":\"AACAPwAAAEA=\"}, {\"index\":1,\"embedding\":[3,4]}", false));
			var provider = new OpenAiEmbeddingProvider(client);

			// when
			var result = provider.send(request(Duration.ofSeconds(2)));

			// then
			assertThat(result.outcome()).isEqualTo(LlmCallOutcome.SUCCEEDED);
			assertThat(result.vectors()).containsExactly(List.of(1f, 2f), List.of(3f, 4f));
			assertThat(result.inputTokens()).isNull();
		}
	}

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
	@DisplayName("HTTP 실패를 채팅과 같은 갈래로 나누고 재시도와 원문 로그를 남기지 않는다")
	void classifiesHttpFailure(int status, String code, String outcome, String failure, CapturedOutput output)
		throws Exception {
		// given
		try (var server = new FakeOpenAiServer();
			var client = new OpenAiChatProvider("fake-key", server.baseUrl(), Duration.ofSeconds(5))) {
			server.respond(status, FakeOpenAiServer.error(code));
			var provider = new OpenAiEmbeddingProvider(client);

			// when
			var result = provider.send(request(Duration.ofSeconds(2)));

			// then
			assertThat(result.outcome()).isEqualTo(LlmCallOutcome.valueOf(outcome));
			assertThat(result.failure()).isEqualTo(GenerationOutcome.Failure.valueOf(failure));
			assertThat(result.inputTokens()).isNull();
			assertThat(server.requests()).hasSize(1);
			assertThat(output).doesNotContain("DEFINITION_PRIVATE_MARKER", "RESPONSE_PRIVATE_MARKER", "fake-key");
		}
	}

	@ParameterizedTest(name = "헤더 먼저: {0}")
	@ValueSource(booleans = {false, true})
	@DisplayName("요청별 시간 제한은 헤더와 본문 모두 적용되며 재시도하지 않는다")
	void timesOutOnce(boolean headersFirst, CapturedOutput output) throws Exception {
		// given
		try (var server = new FakeOpenAiServer();
			var client = new OpenAiChatProvider("fake-key", server.baseUrl(), Duration.ofSeconds(5))) {
			if (headersFirst) {
				server.respondWithDelayedBody(200, "RESPONSE_PRIVATE_MARKER", Duration.ofSeconds(2));
			} else {
				server.respond(200, "RESPONSE_PRIVATE_MARKER", Duration.ofSeconds(2));
			}
			var provider = new OpenAiEmbeddingProvider(client);

			// when
			var result = provider.send(request(Duration.ofMillis(300)));

			// then
			assertThat(result.outcome()).isEqualTo(LlmCallOutcome.TIMEOUT);
			assertThat(result.failure()).isEqualTo(GenerationOutcome.Failure.PROVIDER_UNAVAILABLE);
			assertThat(server.requests()).hasSize(1);
			assertThat(output).doesNotContain("DEFINITION_PRIVATE_MARKER", "RESPONSE_PRIVATE_MARKER", "fake-key");
		}
	}

	@Test
	@DisplayName("연결 오류도 한 번만 요청하고 실패 값으로 돌려준다")
	void handlesDisconnectedResponse() throws Exception {
		// given
		try (var server = new FakeOpenAiServer();
			var client = new OpenAiChatProvider("fake-key", server.baseUrl(), Duration.ofSeconds(5))) {
			var provider = new OpenAiEmbeddingProvider(client);

			// when
			var result = provider.send(request(Duration.ofSeconds(2)));

			// then
			assertThat(result.outcome()).isEqualTo(LlmCallOutcome.PROVIDER_ERROR);
			assertThat(result.failure()).isEqualTo(GenerationOutcome.Failure.PROVIDER_UNAVAILABLE);
			assertThat(server.requests()).hasSize(1);
		}
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("malformedResponses")
	@DisplayName("깨진 JSON과 벡터 및 잘못된 index는 형식 실패로 돌리고 원문을 남기지 않는다")
	void rejectsMalformedResponse(String body, CapturedOutput output) throws Exception {
		// given
		try (var server = new FakeOpenAiServer();
			var client = new OpenAiChatProvider("fake-key", server.baseUrl(), Duration.ofSeconds(5))) {
			server.respond(200, body);
			var provider = new OpenAiEmbeddingProvider(client);

			// when
			var result = provider.send(request(Duration.ofSeconds(2)));

			// then
			assertThat(result.outcome()).isEqualTo(LlmCallOutcome.INVALID_OUTPUT);
			assertThat(result.failure()).isEqualTo(GenerationOutcome.Failure.INVALID_OUTPUT);
			assertThat(server.requests()).hasSize(1);
			assertThat(output).doesNotContain("DEFINITION_PRIVATE_MARKER", "RESPONSE_PRIVATE_MARKER", "fake-key");
		}
	}

	static java.util.stream.Stream<String> malformedResponses() {
		return java.util.stream.Stream.of("RESPONSE_PRIVATE_MARKER",
			"{\"data\":[{\"index\":0,\"embedding\":[1]},{\"index\":0,\"embedding\":[2]}]}",
			"{\"data\":[{\"index\":2,\"embedding\":[1]}]}",
			"{\"data\":[{\"index\":-1,\"embedding\":[1]}]}",
			"{\"data\":[{\"index\":0,\"embedding\":\"RESPONSE_PRIVATE_MARKER\"}]}",
			"{\"data\":[{\"index\":0,\"embedding\":[\"RESPONSE_PRIVATE_MARKER\"]}]}");
	}

	private EmbeddingProvider.Request request(Duration timeout) {
		return new EmbeddingProvider.Request(1, JobType.MATERIAL,
			new EmbeddingSettings("text-embedding-3-large", timeout),
			List.of("DEFINITION_PRIVATE_MARKER", "두 번째 정의"));
	}
}
