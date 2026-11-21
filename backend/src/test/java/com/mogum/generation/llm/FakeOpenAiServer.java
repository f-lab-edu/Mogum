package com.mogum.generation.llm;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 실제 HTTP 요청을 보관한다. 응답이 없으면 연결만 닫아 연결 오류를 재현한다. */
public final class FakeOpenAiServer implements AutoCloseable {
	private final HttpServer httpServer;
	private final java.util.concurrent.ExecutorService executorService = Executors.newCachedThreadPool();
	private final Queue<Response> responses = new ConcurrentLinkedQueue<>();
	private final List<Request> requests = new CopyOnWriteArrayList<>();

	public FakeOpenAiServer() throws IOException {
		httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		httpServer.setExecutor(executorService);
		httpServer.createContext("/", exchange -> {
			var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
				exchange.getRequestHeaders().getFirst("Authorization"), new JsonMapper().readTree(body)));
			var response = responses.poll();
			try (exchange) {
				if (response == null)
					return;
				byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", "application/json");
				if (response.headersFirst())
					exchange.sendResponseHeaders(response.status(), bytes.length);
				try {
					Thread.sleep(response.delay().toMillis());
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					return;
				}
				if (!response.headersFirst())
					exchange.sendResponseHeaders(response.status(), bytes.length);
				exchange.getResponseBody().write(bytes);
			} catch (IOException ignored) {
				// 시간 초과 시험에서 클라이언트가 먼저 닫는다.
			}
		});
		httpServer.start();
	}

	public String baseUrl() {
		return "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/v1";
	}

	public List<Request> requests() {
		return List.copyOf(requests);
	}

	public void respond(int status, String body) {
		respond(status, body, Duration.ZERO);
	}

	public void respond(int status, String body, Duration delay) {
		responses.add(new Response(status, body, delay, false));
	}

	public void respondWithDelayedBody(int status, String body, Duration delay) {
		responses.add(new Response(status, body, delay, true));
	}

	public static String completion(String reason, String content, boolean usage) {
		var json = new JsonMapper();
		return """
			{"id":"chat-test","object":"chat.completion","created":1,"model":"gpt-5.6-terra",
			 "choices":[{"index":0,"finish_reason":%s,"message":{"role":"assistant","content":%s}}]%s}
			""".formatted(json.writeValueAsString(reason), json.writeValueAsString(content), usage ? """
			,"usage":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120,
			          "prompt_tokens_details":{"cached_tokens":40}}
			""" : "");
	}

	public static String error(String code) {
		return """
			{"error":{"message":"RESPONSE_PRIVATE_MARKER","type":"invalid_request_error",
			          "code":"%s","param":null}}
			""".formatted(code);
	}

	@Override
	public void close() {
		httpServer.stop(0);
		executorService.shutdownNow();
	}

	public record Request(String method, String path, String authorization, JsonNode body) {
	}
	private record Response(int status, String body, Duration delay, boolean headersFirst) {
	}
}
