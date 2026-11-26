package com.mogum.auth;

import static com.mogum.support.IntegrationTestConfiguration.FakeGoogle.ORIGIN;
import static com.mogum.support.IntegrationTestConfiguration.FakeGoogle.TOKEN_CALLS;
import static com.mogum.support.IntegrationTestConfiguration.FakeGoogle.email;
import static com.mogum.support.IntegrationTestConfiguration.FakeGoogle.name;
import static com.mogum.support.IntegrationTestConfiguration.FakeGoogle.sub;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.session.Session;
import org.springframework.test.json.JsonAssert;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.web.util.UriComponentsBuilder;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.domain.UserStatus;
import com.mogum.config.CreditProperties;
import com.mogum.support.IntegrationTest;

import tools.jackson.databind.ObjectMapper;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class OAuthLoginHandlerIntegrationTest extends OAuthScenario {
	@Autowired
	CreditProperties creditProperties;

	@Test
	@DisplayName("시작은 이메일과 프로필 범위와 계정 선택과 로컬 콜백과 짧은 쿠키를 쓰고 세션을 만들지 않는다")
	void startsAuthorizationWithMinimalScopeAndShortCookie() throws Exception {
		// when
		var start = start(null);

		// then
		var location = Optional.ofNullable(start.response().getResponseHeaders().getFirst("Location")).orElseThrow();
		assertThat(location.startsWith(ORIGIN + "/authorize?")).isTrue();

		var query = UriComponentsBuilder.fromUriString(location).build().getQueryParams();
		assertThat(query.getFirst("client_id")).isEqualTo("test-google-client");
		assertThat(URLDecoder.decode(query.getFirst("redirect_uri"), StandardCharsets.UTF_8))
			.isEqualTo("http://localhost:5173/auth/google/callback");
		assertThat(URLDecoder.decode(query.getFirst("scope"), StandardCharsets.UTF_8).split(" "))
			.containsExactlyInAnyOrder("email", "profile");
		assertThat(query.getFirst("prompt")).isEqualTo("select_account");
		assertThat(query.getFirst("code_challenge_method")).isEqualTo("S256");
		assertThat(query.getFirst("code_challenge")).isNotNull();
		assertThat(query.getFirst("code_challenge").matches("[A-Za-z0-9_-]{43}")).isTrue();
		assertThat(start.state().isBlank()).isFalse();

		var header = cookieHeader(start.response(), "MOGUM_OAUTH_REQUEST");
		assertCookieSecurity(header);
		assertThat(header).contains("Path=/auth/google");
		assertThat(header).contains("Max-Age=600");
		assertThat(header).doesNotContain("Domain=");

		assertThat(start.response()
			.getResponseHeaders()
			.getOrEmpty("Set-Cookie")
			.stream()
			.noneMatch(value -> value.startsWith("SESSION=")))
			.isTrue();
		assertThat(count("spring_session")).isEqualTo(sessionsBefore);
	}

	@Test
	@DisplayName("기존 회원 콜백은 ID만 담은 세션을 만들고 안전한 성공 로그를 남긴다")
	void createsIdOnlySessionAndLogsSafeSuccess(CapturedOutput output) throws Exception {
		// given
		sub = "LEAKCHECK-oauth-marker";
		userRepository.save(UserFixtures.user("google", sub, email));
		var start = start(null);

		// when
		var response = callback(start, "ok", null);

		// then
		assertRedirect(response, "https://mogum.example/");
		assertExpired(response);

		String sessionHeader = cookieHeader(response, "SESSION");
		assertCookieSecurity(sessionHeader);
		assertThat(sessionHeader).doesNotContain("Domain=");

		String sessionCookie = cookiePair(sessionHeader);
		var row = jdbcTemplate.queryForMap("SELECT id, status FROM users WHERE provider = 'google' AND subject = ?",
			sub);
		assertThat(row.get("status")).isEqualTo("APPLIED");
		assertIdOnly(sessionCookie, row.get("id").toString());
		assertThat(count("users")).isEqualTo(usersBefore + 1);
		assertThat(count("spring_session")).isEqualTo(sessionsBefore + 1);
		assertThat(TOKEN_CALLS.get()).isEqualTo(1);

		var line = loginLine(response, output);
		assertThat(line).contains(" INFO ");
		assertThat(line).contains("login result=success userId=" + row.get("id"));
		assertPrivate(output, email, sub, "fake-access-token", "fake-id-token", sessionCookie,
			start.state(), start.cookie(), verifier(start));
	}

	@Test
	@DisplayName("로그인 콜백이 만든 세션으로 내 정보를 조회한다")
	void authenticatesSessionCreatedByCallback() throws Exception {
		// given
		userRepository.save(UserFixtures.user("google", sub, email));
		var loggedIn = callback(start(null), "ok", null);
		var cookie = cookiePair(cookieHeader(loggedIn, "SESSION"));

		// when
		var response = get("/api/me", cookie);

		// then
		assertThat(response.getStatus().value()).isEqualTo(200);
		assertThat(response.getResponseBody()).contains("admin@example.com");
	}

	@ParameterizedTest(name = "기존 회원 상태 {0}")
	@EnumSource(UserStatus.class)
	@DisplayName("재로그인은 상태와 관계없이 세션 번호를 바꾸고 커밋된 회원 행 전체를 보존한다")
	void rotatesSessionAndPreservesExistingAccount(UserStatus status) throws Exception {
		// given
		var user = userRepository.save(UserFixtures.withStatus(UserFixtures.user("google", sub, email), status));
		var first = callback(start(null), "ok", null);
		var oldCookie = cookiePair(cookieHeader(first, "SESSION"));
		long id = user.getId();
		var before = jdbcTemplate.queryForMap("SELECT * FROM users WHERE id = ?", id);

		testClock.advance(Duration.ofHours(1));
		email = "changed@example.com";
		name = "changed Google name";

		// when
		var second = callback(start(oldCookie), "ok", oldCookie);

		// then
		assertRedirect(second, "https://mogum.example/");
		var newCookie = cookiePair(cookieHeader(second, "SESSION"));
		assertThat(newCookie).isNotEqualTo(oldCookie);
		assertThat(jdbcTemplate.queryForObject(
			"SELECT id FROM users WHERE provider = 'google' AND subject = ?", Long.class, sub)).isEqualTo(id);
		assertThat(jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, id))
			.isEqualTo("admin@example.com");
		assertThat(jdbcTemplate.queryForMap("SELECT * FROM users WHERE id = ?", id)).isEqualTo(before);
		assertThat(count("users")).isEqualTo(usersBefore + 1);
		assertIdOnly(newCookie, Long.toString(id));
		assertThat(count("spring_session")).isEqualTo(sessionsBefore + 1);
	}

	@ParameterizedTest(name = "재로그인 뒤 쿠키 {0}")
	@ValueSource(strings = {"old", "new"})
	@DisplayName("재로그인 뒤 이전 세션은 401이며 새 세션만 저장된 이메일과 상태로 접근한다")
	void authenticatesOnlyRotatedSession(String which) throws Exception {
		// given
		userRepository.save(UserFixtures.user("google", sub, email));
		var first = callback(start(null), "ok", null);
		var oldCookie = cookiePair(cookieHeader(first, "SESSION"));
		jdbcTemplate.update("UPDATE users SET status = 'REJECTED', updated_at = now()");
		email = "changed@example.com";
		name = "changed Google name";
		var second = callback(start(oldCookie), "ok", oldCookie);
		var cookie = which.equals("old") ? oldCookie : cookiePair(cookieHeader(second, "SESSION"));

		// when
		var response = get("/api/me", cookie);

		// then
		assertThat(response.getStatus().value()).isEqualTo(which.equals("old") ? 401 : 200);

		if (which.equals("new")) {
			var body = new ObjectMapper().readTree(response.getResponseBody()).get("data");
			assertThat(body.get("email").asString()).isEqualTo("admin@example.com");
			assertThat(body.get("status").asString()).isEqualTo("REJECTED");
		}
	}

	@Test
	@DisplayName("요청의 돌아갈 주소와 Host는 리다이렉트 대상을 바꾸지 않는다")
	void keepsConfiguredRedirectDespiteUntrustedParameters() throws Exception {
		// when
		var response = get("/auth/google?redirect_uri=https://evil.example&returnTo=https://evil.example", null);

		// then
		assertThat(Optional.ofNullable(response.getResponseHeaders().getFirst("Location"))
			.orElseThrow()
			.contains("redirect_uri=http://localhost:5173/auth/google/callback"))
			.isTrue();
	}

	@Test
	@DisplayName("로그인 시작의 외부 복귀 주소와 Host와 무관하게 콜백은 설정한 화면으로 돌아간다")
	void returnsToConfiguredOriginAfterUntrustedStart() throws Exception {
		// given
		userRepository.save(UserFixtures.user("google", sub, email));
		var start = rememberAuthorization(
			get("/auth/google?redirect_uri=https://evil.example&returnTo=https://evil.example", null));

		// when
		var response = callback(start, "ok", null);

		// then
		assertRedirect(response, "https://mogum.example/");
	}

	@Test
	@DisplayName("신청 전 로그인은 회원 없이 구글 신원만 저장하고 개인정보 없는 INFO 줄을 남긴다")
	void createsNotAppliedSessionAndLogsSafeResult(CapturedOutput output) throws Exception {
		// given
		sub = "LEAKCHECK-sub-marker";
		email = "LEAKCHECK-email-marker@example.com";
		name = "LEAKCHECK-name-marker";
		var start = start(null);

		// when
		var response = callback(start, "ok", null);

		// then
		assertRedirect(response, "https://mogum.example/");
		assertExpired(response);

		var sessionHeader = cookieHeader(response, "SESSION");
		assertCookieSecurity(sessionHeader);
		assertThat(sessionHeader).doesNotContain("Domain=");

		var cookie = cookiePair(sessionHeader);
		assertNotAppliedOnly(cookie);
		assertThat(count("users")).isEqualTo(usersBefore);
		assertThat(count("credit_entries")).isZero();
		assertThat(count("spring_session")).isEqualTo(sessionsBefore + 1);
		assertThat(TOKEN_CALLS.get()).isEqualTo(1);

		var line = loginLine(response, output);
		assertThat(line).contains(" INFO ").endsWith("login result=not_applied").doesNotContain("userId=");
		assertPrivate(output, email, name, sub, "fake-access-token", "fake-id-token", cookie,
			start.state(), start.cookie(), verifier(start));

		var me = get("/api/me", cookie);
		assertThat(me.getStatus().value()).isEqualTo(200);
		JsonAssert.comparator(JsonCompareMode.STRICT)
			.assertIsMatch(
				"""
					{"data":{"status":"NOT_APPLIED",\
					"email":"LEAKCHECK-email-marker@example.com","name":"LEAKCHECK-name-marker"}}
					""",
				me.getResponseBody());
		assertNotAppliedOnly(cookie);
		assertPrivate(output, email, name, sub);
	}

	@ParameterizedTest(name = "구글 이름 [{0}]")
	@NullAndEmptySource
	@ValueSource(strings = {"   "})
	@DisplayName("이름이 없거나 공백인 구글 응답도 이메일 앞부분으로 실제 HTTP 인증을 마친다")
	void usesEmailNameWhenGoogleOmitsName(String googleName) throws Exception {
		// given
		name = googleName;
		email = "test123@gmail.com";
		var loggedIn = callback(start(null), "ok", null);
		var cookie = cookiePair(cookieHeader(loggedIn, "SESSION"));

		// when
		var me = get("/api/me", cookie);

		// then
		assertThat(me.getStatus().value()).isEqualTo(200);
		JsonAssert.comparator(JsonCompareMode.STRICT)
			.assertIsMatch("""
				{"data":{"status":"NOT_APPLIED","email":"test123@gmail.com","name":"test123"}}
				""", me.getResponseBody());
		assertThat(count("users")).isEqualTo(usersBefore);
	}

	@ParameterizedTest(name = "기존 세션 {0}")
	@ValueSource(strings = {"not-applied", "member"})
	@DisplayName("신청 전 로그인도 기존 세션을 회전하고 속성과 주인 색인을 지우며 MDC를 남기지 않는다")
	void rotatesAndClearsSessionForNotAppliedAccount(String previous, CapturedOutput output) throws Exception {
		// given
		if (previous.equals("member")) {
			userRepository.save(UserFixtures.user("google", sub, email));
		}

		var first = callback(start(null), "ok", null);
		var oldCookie = cookiePair(cookieHeader(first, "SESSION"));
		var oldId = new String(Base64.getDecoder().decode(oldCookie.substring("SESSION=".length())),
			StandardCharsets.UTF_8);
		var jdbcSession = jdbcIndexedSessionRepository.findById(oldId);
		Session stored = jdbcSession;
		stored.setAttribute("stale", "must-be-cleared");
		jdbcIndexedSessionRepository.save(jdbcSession);

		sub = "LEAKCHECK-next-sub";
		email = "LEAKCHECK-next-email@example.com";
		name = "LEAKCHECK-next-name";
		var next = start(oldCookie);

		// when
		var response = callback(next, "ok", oldCookie);

		// then
		assertRedirect(response, "https://mogum.example/");
		var newCookie = cookiePair(cookieHeader(response, "SESSION"));
		assertThat(newCookie).isNotEqualTo(oldCookie);
		assertThat(jdbcIndexedSessionRepository.findById(oldId)).isNull();
		assertNotAppliedOnly(newCookie);
		assertThat(count("users")).isEqualTo(usersBefore + (previous.equals("member") ? 1 : 0));
		assertThat(count("spring_session")).isEqualTo(sessionsBefore + 1);
		assertThat(get("/api/me", oldCookie).getStatus().value()).isEqualTo(401);
		assertThat(get("/api/me", newCookie).getStatus().value()).isEqualTo(200);

		assertThat(loginLine(response, output)).endsWith("login result=not_applied").doesNotContain("userId=");
		assertPrivate(output, sub, email, name);
	}

	@Test
	@DisplayName("탈퇴한 구글 계정은 실제 재로그인과 신청 뒤 새 회원과 새 채움 기록으로 시작한다")
	void reappliesAsNewAccountAfterWithdrawal(CapturedOutput output) throws Exception {
		// given
		email = "LEAKCHECK-returning@example.com";
		sub = email;
		name = "LEAKCHECK-returning-name";
		var old = userRepository.save(UserFixtures.approved(email, 17, testClock.instant().plus(Duration.ofDays(7))));
		jdbcTemplate.update("INSERT INTO credit_entries (user_id, type, amount, created_at, updated_at) "
			+ "VALUES (?, 'REFILL', 17, now(), now())", old.getId());
		long oldEntryId = jdbcTemplate.queryForObject("SELECT id FROM credit_entries", Long.class);
		testRequests.asUser(old.getId()).post().uri("/api/account/withdraw").exchange()
			.expectStatus().isNoContent();
		var login = callback(start(null), "ok", null);
		var cookie = cookiePair(cookieHeader(login, "SESSION"));
		assertNotAppliedOnly(cookie);
		assertThat(get("/api/me", cookie).getResponseBody()).contains("\"status\":\"NOT_APPLIED\"");
		assertThat(count("users")).isZero();
		assertThat(count("credit_entries")).isZero();
		var csrf = get("/api/me", cookie).getResponseCookies().getFirst("XSRF-TOKEN");
		// when
		var response = testRequests.anonymous().post().uri("/api/account/apply")
			.header("Cookie", cookie + "; XSRF-TOKEN=" + csrf.getValue())
			.header("X-XSRF-TOKEN", csrf.getValue()).exchange().returnResult(String.class);
		// then
		assertThat(response.getStatus().value()).isEqualTo(200);
		assertThat(response.getResponseBody()).contains("\"status\":\"APPLIED\"");
		var applied = userRepository.findByProviderAndSubject("google", sub).orElseThrow();
		assertThat(applied.getId()).isNotEqualTo(old.getId());
		assertThat(applied.getStatus()).isEqualTo(UserStatus.APPLIED);
		assertThat(applied.getCreditBalance()).isEqualTo(creditProperties.weeklyAmount());
		assertThat(applied.getApprovedAt()).isNull();
		assertThat(applied.getCreditRefillAt()).isNull();
		var entries = jdbcTemplate.queryForList("SELECT * FROM credit_entries");
		assertThat(entries).singleElement().satisfies(entry -> {
			assertThat(entry.get("id")).isNotEqualTo(oldEntryId);
			assertThat(entry.get("user_id")).isEqualTo(applied.getId());
			assertThat(entry.get("type")).isEqualTo("REFILL");
			assertThat(entry.get("amount")).isEqualTo(applied.getCreditBalance());
			assertThat(entry.get("job_id")).isNull();
		});
		assertThat(userRepository.findById(old.getId())).isEmpty();
		assertIdOnly(cookiePair(cookieHeader(response, "SESSION")), applied.getId().toString());
		assertPrivate(output, email, name, sub);
	}

	private void assertNotAppliedOnly(String cookie) {
		String id = new String(Base64.getDecoder().decode(cookie.substring("SESSION=".length())),
			StandardCharsets.UTF_8);

		assertThat(jdbcTemplate.queryForObject(
			"SELECT principal_name FROM spring_session WHERE session_id = ?", String.class, id)).isNull();
		Session stored = jdbcIndexedSessionRepository.findById(id);
		assertThat(stored).isNotNull();
		assertThat(stored.getAttributeNames()).containsExactly(AccountPrincipal.NOT_APPLIED_SESSION_ATTRIBUTE);
		assertThat((Object)stored.getAttribute(AccountPrincipal.NOT_APPLIED_SESSION_ATTRIBUTE))
			.isEqualTo(new AccountPrincipal.NotApplied("google", sub, email, name));
	}
}
