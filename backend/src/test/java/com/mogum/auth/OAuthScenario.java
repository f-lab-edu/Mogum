package com.mogum.auth;

import static com.mogum.support.IntegrationTestConfiguration.FakeGoogle.CODE_CHALLENGE;
import static com.mogum.support.IntegrationTestConfiguration.FakeGoogle.TOKEN_CALLS;
import static com.mogum.support.IntegrationTestConfiguration.FakeGoogle.email;
import static com.mogum.support.IntegrationTestConfiguration.FakeGoogle.name;
import static com.mogum.support.IntegrationTestConfiguration.FakeGoogle.sub;
import static com.mogum.support.IntegrationTestConfiguration.FakeGoogle.tokenFailure;
import static com.mogum.support.IntegrationTestConfiguration.FakeGoogle.userInfoFailure;
import static com.mogum.support.IntegrationTestConfiguration.FakeGoogle.verified;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.web.util.UriComponentsBuilder;

import com.mogum.account.repository.UserRepository;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestClock;
import com.mogum.support.TestRequests;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
abstract class OAuthScenario {
	@Value("${local.server.port}")
	int port;
	@Autowired
	TestRequests testRequests;
	@Autowired
	UserRepository userRepository;
	@Autowired
	JdbcTemplate jdbcTemplate;
	@Autowired
	JdbcIndexedSessionRepository jdbcIndexedSessionRepository;
	@Autowired
	TestClock testClock;
	int usersBefore;
	int sessionsBefore;

	@BeforeEach
	void resetProvider() {
		sub = "google-sub";
		email = "admin@example.com";
		name = "Google name";
		verified = true;
		tokenFailure = false;
		userInfoFailure = false;
		TOKEN_CALLS.set(0);
		CODE_CHALLENGE.set(null);

		usersBefore = count("users");
		sessionsBefore = count("spring_session");
	}

	static String loginLine(EntityExchangeResult<?> response, CapturedOutput output) throws InterruptedException {
		String id = Optional.ofNullable(response.getResponseHeaders().getFirst("X-Request-Id")).orElseThrow();
		long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();

		while (output.getOut().lines().noneMatch(line -> line.contains("requestId=" + id)
			&& line.contains("access method=")) && System.nanoTime() < deadline) {
			Thread.sleep(10);
		}

		assertThat(output.getOut().lines().filter(line -> line.contains("requestId=" + id)
			&& line.contains("access method=")).count()).isEqualTo(1);

		var lines = output.getOut()
			.lines()
			.filter(line -> line.contains("requestId=" + id) && line.contains("login result="))
			.toList();
		assertThat(lines).hasSize(1);
		return lines.getFirst();
	}

	static void assertPrivate(CapturedOutput output, String... forbidden) {
		for (String value : forbidden) {
			assertThat(output.getAll().contains(value)).isFalse();
		}
	}

	static String verifier(Start start) {
		return new ObjectMapper().readTree(Base64.getUrlDecoder().decode(
			start.cookie().substring("MOGUM_OAUTH_REQUEST=".length())))
			.get("code_verifier")
			.asString();
	}

	String expiredCookie(Start start) {
		var mapper = new ObjectMapper();
		var saved = (ObjectNode)mapper.readTree(Base64.getUrlDecoder().decode(
			start.cookie().substring("MOGUM_OAUTH_REQUEST=".length())));
		saved.put("issuedAt", testClock.instant().minusSeconds(601).getEpochSecond());
		return "MOGUM_OAUTH_REQUEST=" + Base64.getUrlEncoder()
			.withoutPadding()
			.encodeToString(mapper.writeValueAsBytes(saved));
	}

	Start withVerifier(Start start, String verifier) {
		var mapper = new ObjectMapper();
		var saved = (ObjectNode)mapper.readTree(
			Base64.getUrlDecoder().decode(start.cookie().substring("MOGUM_OAUTH_REQUEST=".length())));

		if (verifier == null) {
			saved.remove("code_verifier");
		} else {
			saved.put("code_verifier", verifier);
		}

		String cookie = "MOGUM_OAUTH_REQUEST=" + Base64.getUrlEncoder()
			.withoutPadding()
			.encodeToString(mapper.writeValueAsBytes(saved));
		return new Start(start.state(), cookie, start.response());
	}

	void assertIdOnly(String cookie, String id) {
		String sessionId = new String(Base64.getDecoder().decode(cookie.substring("SESSION=".length())),
			StandardCharsets.UTF_8);

		assertThat(jdbcTemplate.queryForObject(
			"SELECT principal_name FROM spring_session WHERE session_id = ?", String.class, sessionId))
			.isEqualTo(id);
		Session stored = jdbcIndexedSessionRepository.findById(sessionId);
		assertThat(stored).isNotNull();
		assertThat(stored.getAttributeNames())
			.isEqualTo(Set.of(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME));
		assertThat((String)stored.getAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME))
			.isEqualTo(id);

		List<byte[]> attributes = jdbcTemplate.query(
			"SELECT attribute_bytes FROM spring_session_attributes WHERE session_primary_id "
				+ "= (SELECT primary_id FROM spring_session WHERE session_id = ?)",
			(rs, row) -> rs.getBytes(1), sessionId);
		assertThat(attributes).hasSize(1);

		String bytes = new String(attributes.getFirst(), StandardCharsets.ISO_8859_1);
		for (String forbidden : List.of(email, sub, name, "fake-access-token", "fake-id-token")) {
			assertThat(bytes).doesNotContain(forbidden);
		}
	}

	Start start(String existingCookie) throws Exception {
		var response = get("/auth/google", existingCookie);
		assertThat(response.getStatus().value()).isEqualTo(302);
		return rememberAuthorization(response);
	}

	Start rememberAuthorization(EntityExchangeResult<String> response) {
		var query = UriComponentsBuilder.fromUriString(
			Optional.ofNullable(response.getResponseHeaders().getFirst("Location")).orElseThrow())
			.build()
			.getQueryParams();
		CODE_CHALLENGE.set(query.getFirst("code_challenge"));
		return new Start(query.getFirst("state"), cookiePair(cookieHeader(response, "MOGUM_OAUTH_REQUEST")), response);
	}

	EntityExchangeResult<String> callback(Start start, String code, String existingCookie) throws Exception {
		return get("/auth/google/callback?code=" + code + "&state=" + start.state(),
			start.cookie() + (existingCookie == null ? "" : "; " + existingCookie));
	}

	EntityExchangeResult<String> get(String path, String cookie) {
		var request = testRequests.anonymous().get().uri(URI.create("http://localhost:" + port + path));
		if (cookie != null) {
			request.header("Cookie", cookie);
		}

		return request.exchange().returnResult(String.class);
	}

	int count(String table) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
	}

	static String cookieHeader(EntityExchangeResult<?> response, String name) {
		var header = response.getResponseHeaders()
			.getOrEmpty("Set-Cookie")
			.stream()
			.filter(value -> value.startsWith(name + "="))
			.findFirst();
		assertThat(header.isPresent()).isTrue();
		return header.orElseThrow();
	}

	static String cookiePair(String header) {
		return header.split(";", 2)[0];
	}

	static void assertCookieSecurity(String header) {
		assertThat(header).contains("HttpOnly");
		assertThat(header).contains("Secure");
		assertThat(header).contains("SameSite=Lax");
	}

	static void assertRedirect(EntityExchangeResult<?> response, String target) {
		assertThat(response.getStatus().value()).isEqualTo(302);
		assertThat(Optional.ofNullable(response.getResponseHeaders().getFirst("Location")).orElseThrow())
			.isEqualTo(target);
	}

	static void assertExpired(EntityExchangeResult<?> response) {
		String cookie = cookieHeader(response, "MOGUM_OAUTH_REQUEST");
		assertThat(cookie).contains("Max-Age=0");
		assertCookieSecurity(cookie);
		assertThat(cookie).contains("Path=/auth/google");
	}

	void assertFailure(EntityExchangeResult<?> response, String error) {
		assertRedirect(response, "https://mogum.example/login?error=" + error);
		assertExpired(response);

		assertThat(count("users")).isEqualTo(usersBefore);
		assertThat(count("spring_session")).isEqualTo(sessionsBefore);
		assertThat(response.getResponseHeaders()
			.getOrEmpty("Set-Cookie")
			.stream()
			.noneMatch(value -> value.startsWith("SESSION=")))
			.isTrue();
	}

	record Start(String state, String cookie, EntityExchangeResult<String> response) {
	}
}
