package com.mogum.auth;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.domain.UserStatus;
import com.mogum.account.repository.UserRepository;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestRequests;
import com.mogum.support.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static com.mogum.support.IntegrationTestConfiguration.FakeGoogle.*;
import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class OAuthLoginHandlerIntegrationTest extends OAuthScenario {
    @Test
    @DisplayName("시작은 최소 범위와 로컬 콜백과 짧은 쿠키를 쓰고 세션을 만들지 않는다")
    void startsAuthorizationWithMinimalScopeAndShortCookie() throws Exception {
        // when
        var start = start(null);
        // then
        var location = java.util.Optional.ofNullable(start.response().getResponseHeaders().getFirst("Location")).orElseThrow();
        assertThat(location.startsWith(ORIGIN + "/authorize?")).isTrue();
        var query = UriComponentsBuilder.fromUriString(location).build().getQueryParams();
        assertThat(query.getFirst("client_id")).isEqualTo("test-google-client");
        assertThat(java.net.URLDecoder.decode(query.getFirst("redirect_uri"), StandardCharsets.UTF_8)).isEqualTo("http://localhost:5173/auth/google/callback");
        assertThat(query.getFirst("scope")).isEqualTo("email");
        assertThat(query.getFirst("code_challenge_method")).isEqualTo("S256");
        assertThat(query.getFirst("code_challenge")).isNotNull();
        assertThat(query.getFirst("code_challenge").matches("[A-Za-z0-9_-]{43}")).isTrue();
        assertThat(start.state().isBlank()).isFalse();
        var header = cookieHeader(start.response(), "MOGUM_OAUTH_REQUEST");
        assertCookieSecurity(header);
        assertThat(header).contains("Path=/auth/google");
        assertThat(header).contains("Max-Age=600");
        assertThat(header).doesNotContain("Domain=");
        assertThat(start.response().getResponseHeaders().getOrEmpty("Set-Cookie").stream().noneMatch(value -> value.startsWith("SESSION="))).isTrue();
        assertThat(count("spring_session")).isEqualTo(sessionsBefore);
    }

    @Test
    @DisplayName("기존 회원 콜백은 ID만 담은 세션을 만들고 안전한 성공 로그를 남긴다")
    void createsIdOnlySessionAndLogsSafeSuccess(CapturedOutput output) throws Exception {
        // given
        sub = "LEAKCHECK-oauth-marker";
        users.save(UserFixtures.user("google", sub, email));
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
        var row = jdbc.queryForMap("SELECT id, status FROM users WHERE provider = 'google' AND subject = ?", sub);
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
        users.save(UserFixtures.user("google", sub, email));
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
        var user = users.save(UserFixtures.withStatus(UserFixtures.user("google", sub, email), status));
        var first = callback(start(null), "ok", null);
        var oldCookie = cookiePair(cookieHeader(first, "SESSION"));
        long id = user.getId();
        var before = jdbc.queryForMap("SELECT * FROM users WHERE id = ?", id);
        clock.advance(Duration.ofHours(1));
        email = "changed@example.com";
        // when
        var second = callback(start(oldCookie), "ok", oldCookie);
        // then
        assertRedirect(second, "https://mogum.example/");
        var newCookie = cookiePair(cookieHeader(second, "SESSION"));
        assertThat(newCookie).isNotEqualTo(oldCookie);
        assertThat(jdbc.queryForObject("SELECT id FROM users WHERE provider = 'google' AND subject = ?", Long.class, sub)).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, id)).isEqualTo("admin@example.com");
        assertThat(jdbc.queryForMap("SELECT * FROM users WHERE id = ?", id)).isEqualTo(before);
        assertThat(count("users")).isEqualTo(usersBefore + 1);
        assertIdOnly(newCookie, Long.toString(id));
        assertThat(count("spring_session")).isEqualTo(sessionsBefore + 1);
    }

    @ParameterizedTest(name = "재로그인 뒤 쿠키 {0}")
    @ValueSource(strings = {"old", "new"})
    @DisplayName("재로그인 뒤 이전 세션은 401이며 새 세션만 저장된 이메일과 상태로 접근한다")
    void authenticatesOnlyRotatedSession(String which) throws Exception {
        // given
        users.save(UserFixtures.user("google", sub, email));
        var first = callback(start(null), "ok", null);
        var oldCookie = cookiePair(cookieHeader(first, "SESSION"));
        jdbc.update("UPDATE users SET status = 'REJECTED', updated_at = now()");
        email = "changed@example.com";
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
        assertThat(java.util.Optional.ofNullable(response.getResponseHeaders().getFirst("Location")).orElseThrow().contains("redirect_uri=http://localhost:5173/auth/google/callback")).isTrue();

    }

    @Test
    @DisplayName("로그인 시작의 외부 복귀 주소와 Host와 무관하게 콜백은 설정한 화면으로 돌아간다")
    void returnsToConfiguredOriginAfterUntrustedStart() throws Exception {
        // given
        users.save(UserFixtures.user("google", sub, email));
        var start = rememberAuthorization(get("/auth/google?redirect_uri=https://evil.example&returnTo=https://evil.example", null));
        // when
        var response = callback(start, "ok", null);
        // then
        assertRedirect(response, "https://mogum.example/");
    }

}

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
abstract class OAuthScenario {
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
    @Autowired TestRequests requests;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcIndexedSessionRepository sessions;
    @Autowired TestClock clock;
    int usersBefore;
    int sessionsBefore;

    @BeforeEach
    void resetProvider() {
        sub = "google-sub";
        email = "admin@example.com";
        verified = true;
        tokenFailure = false;
        userInfoFailure = false;
        TOKEN_CALLS.set(0);
        CODE_CHALLENGE.set(null);
        usersBefore = count("users");
        sessionsBefore = count("spring_session");
    }
    static String loginLine(EntityExchangeResult<?> response, CapturedOutput output) throws InterruptedException {
        String id = java.util.Optional.ofNullable(response.getResponseHeaders().getFirst("X-Request-Id")).orElseThrow();
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (output.getOut().lines().noneMatch(line -> line.contains("requestId=" + id)
                && line.contains("access method=")) && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(output.getOut().lines().filter(line -> line.contains("requestId=" + id)
                && line.contains("access method=")).count()).isEqualTo(1);
        var lines = output.getOut().lines().filter(line -> line.contains("requestId=" + id)
                && line.contains("login result=")).toList();
        assertThat(lines).hasSize(1);
        return lines.getFirst();
    }

    static void assertPrivate(CapturedOutput output, String... forbidden) {
        for (String value : forbidden) assertThat(output.getAll().contains(value)).isFalse();
    }

    static String verifier(Start start) {
        return new ObjectMapper().readTree(Base64.getUrlDecoder().decode(
                start.cookie().substring("MOGUM_OAUTH_REQUEST=".length()))).get("code_verifier").asString();
    }

    String expiredCookie(Start start) {
        var mapper = new ObjectMapper();
        var saved = (tools.jackson.databind.node.ObjectNode) mapper.readTree(Base64.getUrlDecoder().decode(
                start.cookie().substring("MOGUM_OAUTH_REQUEST=".length())));
        saved.put("issuedAt", clock.instant().minusSeconds(601).getEpochSecond());
        return "MOGUM_OAUTH_REQUEST=" + Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(saved));
    }

    Start withVerifier(Start start, String verifier) {
        var mapper = new ObjectMapper();
        var saved = (tools.jackson.databind.node.ObjectNode) mapper.readTree(
                Base64.getUrlDecoder().decode(start.cookie().substring("MOGUM_OAUTH_REQUEST=".length())));
        if (verifier == null) saved.remove("code_verifier");
        else saved.put("code_verifier", verifier);
        String cookie = "MOGUM_OAUTH_REQUEST=" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mapper.writeValueAsBytes(saved));
        return new Start(start.state(), cookie, start.response());
    }

    void assertIdOnly(String cookie, String id) {
        String sessionId = new String(Base64.getDecoder().decode(cookie.substring("SESSION=".length())), StandardCharsets.UTF_8);
        assertThat(jdbc.queryForObject("SELECT principal_name FROM spring_session WHERE session_id = ?", String.class, sessionId)).isEqualTo(id);
        org.springframework.session.Session stored = sessions.findById(sessionId);
        assertThat(stored).isNotNull();
        assertThat(stored.getAttributeNames()).isEqualTo(Set.of(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME));
        assertThat((String) stored.getAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME)).isEqualTo(id);
        List<byte[]> attributes = jdbc.query("SELECT attribute_bytes FROM spring_session_attributes WHERE session_primary_id = (SELECT primary_id FROM spring_session WHERE session_id = ?)",
                (rs, row) -> rs.getBytes(1), sessionId);
        assertThat(attributes).hasSize(1);
        String bytes = new String(attributes.getFirst(), StandardCharsets.ISO_8859_1);
        for (String forbidden : List.of(email, sub, "fake-access-token", "fake-id-token")) assertThat(bytes).doesNotContain(forbidden);
    }

    Start start(String existingCookie) throws Exception {
        var response = get("/auth/google", existingCookie);
        assertThat(response.getStatus().value()).isEqualTo(302);
        return rememberAuthorization(response);
    }

    Start rememberAuthorization(EntityExchangeResult<String> response) {
        var query = UriComponentsBuilder.fromUriString(java.util.Optional.ofNullable(response.getResponseHeaders().getFirst("Location")).orElseThrow())
                .build().getQueryParams();
        CODE_CHALLENGE.set(query.getFirst("code_challenge"));
        return new Start(query.getFirst("state"), cookiePair(cookieHeader(response, "MOGUM_OAUTH_REQUEST")), response);
    }

    EntityExchangeResult<String> callback(Start start, String code, String existingCookie) throws Exception {
        return get("/auth/google/callback?code=" + code + "&state=" + start.state(),
                start.cookie() + (existingCookie == null ? "" : "; " + existingCookie));
    }

    EntityExchangeResult<String> get(String path, String cookie) {
        var request = requests.anonymous().get().uri(java.net.URI.create("http://localhost:" + port + path));
        if (cookie != null) request.header("Cookie", cookie);
        return request.exchange().returnResult(String.class);
    }

    int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    static String cookieHeader(EntityExchangeResult<?> response, String name) {
        var header = response.getResponseHeaders().getOrEmpty("Set-Cookie").stream().filter(value -> value.startsWith(name + "=")).findFirst();
        assertThat(header.isPresent()).isTrue();
        return header.orElseThrow();
    }

    static String cookiePair(String header) { return header.split(";", 2)[0]; }

    static void assertCookieSecurity(String header) {
        assertThat(header).contains("HttpOnly");
        assertThat(header).contains("Secure");
        assertThat(header).contains("SameSite=Lax");
    }

    static void assertRedirect(EntityExchangeResult<?> response, String target) {
        assertThat(response.getStatus().value()).isEqualTo(302);
        assertThat(java.util.Optional.ofNullable(response.getResponseHeaders().getFirst("Location")).orElseThrow()).isEqualTo(target);
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
        assertThat(response.getResponseHeaders().getOrEmpty("Set-Cookie").stream().noneMatch(value -> value.startsWith("SESSION="))).isTrue();
    }

    record Start(String state, String cookie, EntityExchangeResult<String> response) { }
}
