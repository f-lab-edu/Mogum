package com.mogum.api.auth;

import com.mogum.support.PostgresTestConfiguration;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 실제 HTTP 필터 체인을 통과하며 Google만 루프백 HTTP 서버로 바꾼다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.web.origin=https://mogum.example",
        "app.auth.admin-emails=admin@example.com,changed@example.com"
})
class OAuthLoginHandlerIT extends PostgresTestConfiguration {
    private static final AtomicInteger TOKEN_CALLS = new AtomicInteger();
    private static final AtomicReference<String> CODE_CHALLENGE = new AtomicReference<>();
    private static final HttpServer GOOGLE = fakeGoogle();
    private static final String GOOGLE_ORIGIN = "http://127.0.0.1:" + GOOGLE.getAddress().getPort();
    private static String sub;
    private static String email;
    private static boolean verified;
    @Value("${local.server.port}") private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JdbcIndexedSessionRepository sessions;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private int usersBefore;
    private int sessionsBefore;

    @DynamicPropertySource
    static void googleEndpoints(DynamicPropertyRegistry properties) {
        properties.add("spring.security.oauth2.client.provider.google.authorization-uri", () -> GOOGLE_ORIGIN + "/authorize");
        properties.add("spring.security.oauth2.client.provider.google.token-uri", () -> GOOGLE_ORIGIN + "/token");
        properties.add("spring.security.oauth2.client.provider.google.user-info-uri", () -> GOOGLE_ORIGIN + "/userinfo");
    }

    @BeforeEach
    void before() {
        sub = "google-sub-" + UUID.randomUUID();
        email = "admin@example.com";
        verified = true;
        TOKEN_CALLS.set(0);
        CODE_CHALLENGE.set(null);
        usersBefore = count("users");
        sessionsBefore = count("spring_session");
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM spring_session WHERE principal_name IN (SELECT id::text FROM users WHERE provider = 'google' AND subject = ?)", sub);
        jdbc.update("DELETE FROM users WHERE provider = 'google' AND subject = ?", sub);
    }

    @AfterAll
    static void stopGoogle() {
        GOOGLE.stop(0);
    }

    @Test
    void 시작은_최소_범위와_로컬_콜백과_짧은_쿠키를_쓰고_세션을_만들지_않는다() throws Exception {
        var start = start(null);
        var location = start.response().headers().firstValue("Location").orElseThrow();
        assertTrue(location.startsWith(GOOGLE_ORIGIN + "/authorize?"));
        var query = UriComponentsBuilder.fromUriString(location).build().getQueryParams();
        assertEquals("test-google-client", query.getFirst("client_id"));
        assertEquals("http://localhost:5173/auth/google/callback", java.net.URLDecoder.decode(query.getFirst("redirect_uri"), StandardCharsets.UTF_8));
        assertEquals("email", query.getFirst("scope"));
        assertEquals("S256", query.getFirst("code_challenge_method"));
        assertNotNull(query.getFirst("code_challenge"));
        assertTrue(query.getFirst("code_challenge").matches("[A-Za-z0-9_-]{43}"));
        assertFalse(start.state().isBlank());
        var header = cookieHeader(start.response(), "MOGUM_OAUTH_REQUEST");
        assertCookieSecurity(header);
        assertTrue(header.contains("Path=/auth/google"));
        assertTrue(header.contains("Max-Age=600"));
        assertFalse(header.contains("Domain="));
        assertTrue(start.response().headers().allValues("Set-Cookie").stream().noneMatch(value -> value.startsWith("SESSION=")));
        assertEquals(sessionsBefore, count("spring_session"));
    }

    @Test
    void 관리자_콜백은_사용자와_ID만_담은_세션을_만들고_me로_이어진다() throws Exception {
        var start = start(null);
        var response = callback(start, "ok", null);
        assertRedirect(response, "https://mogum.example/");
        assertExpired(response);
        String sessionHeader = cookieHeader(response, "SESSION");
        assertCookieSecurity(sessionHeader);
        assertFalse(sessionHeader.contains("Domain="));
        String sessionCookie = cookiePair(sessionHeader);
        var row = jdbc.queryForMap("SELECT id, status, role FROM users WHERE provider = 'google' AND subject = ?", sub);
        assertEquals("ACTIVE", row.get("status"));
        assertEquals("ADMIN", row.get("role"));
        assertIdOnly(sessionCookie, row.get("id").toString());
        var me = get("/api/me", sessionCookie);
        assertEquals(200, me.statusCode());
        assertTrue(me.body().contains("admin@example.com"));
        assertEquals(usersBefore + 1, count("users"));
        assertEquals(sessionsBefore + 1, count("spring_session"));
        assertEquals(1, TOKEN_CALLS.get());
    }

    @Test
    void 재로그인은_세션_번호만_바꾸고_같은_sub의_저장된_이메일과_역할을_보존한다() throws Exception {
        var first = callback(start(null), "ok", null);
        var oldCookie = cookiePair(cookieHeader(first, "SESSION"));
        long id = jdbc.queryForObject("SELECT id FROM users WHERE provider = 'google' AND subject = ?", Long.class, sub);
        jdbc.update("UPDATE users SET role = 'USER' WHERE id = ?", id);
        email = "changed@example.com";
        var second = callback(start(oldCookie), "ok", oldCookie);
        assertRedirect(second, "https://mogum.example/");
        var newCookie = cookiePair(cookieHeader(second, "SESSION"));
        assertNotEquals(oldCookie, newCookie);
        assertEquals(401, get("/api/me", oldCookie).statusCode());
        var me = get("/api/me", newCookie);
        assertEquals(200, me.statusCode());
        var body = new tools.jackson.databind.ObjectMapper().readTree(me.body());
        assertEquals("admin@example.com", body.get("email").asString());
        assertEquals("USER", body.get("role").asString());
        assertEquals(id, jdbc.queryForObject("SELECT id FROM users WHERE provider = 'google' AND subject = ?", Long.class, sub));
        assertEquals("admin@example.com", jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, id));
        assertEquals("USER", jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, id));
        assertIdOnly(newCookie, Long.toString(id));
        assertEquals(sessionsBefore + 1, count("spring_session"));
    }

    @Test
    void 관리자_밖_계정은_행을_남기지_않고_not_allowed로_보낸다() throws Exception {
        email = "outside@example.com";
        assertFailure(callback(start(null), "ok", null), "not_allowed");
    }

    @Test
    void 이메일_미인증은_행을_남기지_않고_login_failed로_보낸다() throws Exception {
        verified = false;
        assertFailure(callback(start(null), "ok", null), "login_failed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-state", "no-cookie", "corrupt-cookie", "cancel", "missing-code"})
    void 유효한_요청이_없는_콜백은_토큰_교환_전에_실패한다(String condition) throws Exception {
        var start = start(null);
        String state = condition.equals("wrong-state") ? "wrong" : start.state();
        String cookie = switch (condition) {
            case "no-cookie" -> null;
            case "corrupt-cookie" -> "MOGUM_OAUTH_REQUEST=broken";
            default -> start.cookie();
        };
        String params = condition.equals("cancel") ? "error=access_denied" : condition.equals("missing-code") ? "" : "code=ok";
        assertFailure(get("/auth/google/callback?" + params + "&state=" + state, cookie), "login_failed");
        assertEquals(0, TOKEN_CALLS.get());
    }

    @Test
    void 토큰_교환_실패도_U1으로_돌아간다() throws Exception {
        assertFailure(callback(start(null), "bad-token", null), "login_failed");
        assertEquals(1, TOKEN_CALLS.get());
    }

    @Test
    void 형식이_유효해도_바뀐_verifier는_가짜_Google이_거절한다() throws Exception {
        var start = start(null);
        assertFailure(callback(withVerifier(start, "a".repeat(64)), "ok", null), "login_failed");
        assertEquals(1, TOKEN_CALLS.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "short", "long", "invalid-character"})
    void 없거나_형식이_틀린_verifier는_토큰_교환_전에_실패한다(String condition) throws Exception {
        var start = start(null);
        String verifier = switch (condition) {
            case "missing" -> null;
            case "short" -> "a".repeat(42);
            case "long" -> "a".repeat(129);
            default -> "a".repeat(43) + "!";
        };
        assertFailure(callback(withVerifier(start, verifier), "ok", null), "login_failed");
        assertEquals(0, TOKEN_CALLS.get());
    }

    @Test
    void 가짜_Google도_verifier가_없는_토큰_요청을_거절한다() throws Exception {
        start(null);
        var request = HttpRequest.newBuilder(URI.create(GOOGLE_ORIGIN + "/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("code=ok&grant_type=authorization_code")).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode());
        assertEquals("invalid_grant", new ObjectMapper().readTree(response.body()).get("error").asText());
        assertEquals(usersBefore, count("users"));
        assertEquals(sessionsBefore, count("spring_session"));
    }

    private Start withVerifier(Start start, String verifier) {
        var mapper = new ObjectMapper();
        var saved = (tools.jackson.databind.node.ObjectNode) mapper.readTree(
                Base64.getUrlDecoder().decode(start.cookie().substring("MOGUM_OAUTH_REQUEST=".length())));
        if (verifier == null) saved.remove("code_verifier");
        else saved.put("code_verifier", verifier);
        String cookie = "MOGUM_OAUTH_REQUEST=" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mapper.writeValueAsBytes(saved));
        return new Start(start.state(), cookie, start.response());
    }

    @Test
    void 요청의_돌아갈_주소와_Host는_리다이렉트_대상을_바꾸지_않는다() throws Exception {
        var response = get("/auth/google?redirect_uri=https://evil.example&returnTo=https://evil.example", null);
        assertTrue(response.headers().firstValue("Location").orElseThrow().contains("redirect_uri=http://localhost:5173/auth/google/callback"));
        var start = rememberAuthorization(response);
        assertRedirect(callback(start, "ok", null), "https://mogum.example/");
        assertEquals(401, get("/login", null).statusCode());
    }

    private void assertIdOnly(String cookie, String id) {
        String sessionId = new String(Base64.getDecoder().decode(cookie.substring("SESSION=".length())), StandardCharsets.UTF_8);
        assertEquals(id, jdbc.queryForObject("SELECT principal_name FROM spring_session WHERE session_id = ?", String.class, sessionId));
        org.springframework.session.Session stored = sessions.findById(sessionId);
        assertNotNull(stored);
        assertEquals(Set.of(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME), stored.getAttributeNames());
        assertEquals(id, stored.getAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME));
        List<byte[]> attributes = jdbc.query("SELECT attribute_bytes FROM spring_session_attributes WHERE session_primary_id = (SELECT primary_id FROM spring_session WHERE session_id = ?)",
                (rs, row) -> rs.getBytes(1), sessionId);
        assertEquals(1, attributes.size());
        String bytes = new String(attributes.getFirst(), StandardCharsets.ISO_8859_1);
        for (String forbidden : List.of(email, sub, "fake-access-token", "fake-id-token")) assertFalse(bytes.contains(forbidden));
    }

    private Start start(String existingCookie) throws Exception {
        var response = get("/auth/google", existingCookie);
        assertEquals(302, response.statusCode());
        return rememberAuthorization(response);
    }

    private Start rememberAuthorization(HttpResponse<String> response) {
        var query = UriComponentsBuilder.fromUriString(response.headers().firstValue("Location").orElseThrow())
                .build().getQueryParams();
        CODE_CHALLENGE.set(query.getFirst("code_challenge"));
        return new Start(query.getFirst("state"), cookiePair(cookieHeader(response, "MOGUM_OAUTH_REQUEST")), response);
    }

    private HttpResponse<String> callback(Start start, String code, String existingCookie) throws Exception {
        return get("/auth/google/callback?code=" + code + "&state=" + start.state(),
                start.cookie() + (existingCookie == null ? "" : "; " + existingCookie));
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (cookie != null) request.header("Cookie", cookie);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static String cookieHeader(HttpResponse<?> response, String name) {
        var header = response.headers().allValues("Set-Cookie").stream().filter(value -> value.startsWith(name + "=")).findFirst();
        assertTrue(header.isPresent(), () -> "Missing " + name + ": " + response.headers().map());
        return header.orElseThrow();
    }

    private static String cookiePair(String header) { return header.split(";", 2)[0]; }

    private static void assertCookieSecurity(String header) {
        assertTrue(header.contains("HttpOnly"));
        assertTrue(header.contains("Secure"));
        assertTrue(header.contains("SameSite=Lax"));
    }

    private static void assertRedirect(HttpResponse<?> response, String target) {
        assertEquals(302, response.statusCode());
        assertEquals(target, response.headers().firstValue("Location").orElseThrow());
    }

    private static void assertExpired(HttpResponse<?> response) {
        String cookie = cookieHeader(response, "MOGUM_OAUTH_REQUEST");
        assertTrue(cookie.contains("Max-Age=0"));
        assertCookieSecurity(cookie);
        assertTrue(cookie.contains("Path=/auth/google"));
    }

    private void assertFailure(HttpResponse<?> response, String error) {
        assertRedirect(response, "https://mogum.example/login?error=" + error);
        assertExpired(response);
        assertEquals(usersBefore, count("users"));
        assertEquals(sessionsBefore, count("spring_session"));
        assertTrue(response.headers().allValues("Set-Cookie").stream().noneMatch(value -> value.startsWith("SESSION=")));
    }

    private static HttpServer fakeGoogle() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/token", exchange -> {
                TOKEN_CALLS.incrementAndGet();
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                var form = UriComponentsBuilder.fromUriString("http://localhost/?" + body).build().getQueryParams();
                String verifier = form.getFirst("code_verifier");
                if (verifier != null) verifier = java.net.URLDecoder.decode(verifier, StandardCharsets.UTF_8);
                boolean bad = "bad-token".equals(form.getFirst("code")) || verifier == null
                        || CODE_CHALLENGE.get() == null || !CODE_CHALLENGE.get().equals(challenge(verifier));
                String response = bad ? "{\"error\":\"invalid_grant\"}" : "{\"access_token\":\"fake-access-token\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"id_token\":\"fake-id-token\"}";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(bad ? 400 : 200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.createContext("/userinfo", exchange -> {
                String response = "{\"sub\":\"" + sub + "\",\"email\":\"" + email + "\",\"email_verified\":" + verified + "}";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private record Start(String state, String cookie, HttpResponse<String> response) {}
}
