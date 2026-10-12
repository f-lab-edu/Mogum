package com.mogum.api.auth;

import com.mogum.api.account.MeController;
import com.mogum.core.TransactionRunner;
import com.mogum.core.account.User;
import com.mogum.core.account.UserRepository;
import com.mogum.core.account.UserRole;
import com.mogum.core.account.UserStatus;
import com.mogum.support.PostgresTestConfiguration;
import com.mogum.support.TestSessions;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SecurityConfigurationIT.ServletErrors.class)
class SecurityConfigurationIT extends PostgresTestConfiguration {
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcIndexedSessionRepository sessions;
    @Autowired private CookieSerializer cookieSerializer;
    @Autowired private UserRepository users;
    @Autowired private TransactionRunner transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Flyway flyway;
    @Autowired private ObjectMapper mapper;
    @MockitoSpyBean private MeController meController;
    @Value("${local.server.port}") private int port;
    private MockMvc mvc;
    private TestSessions testSessions;
    private User user;
    private final List<Cookie> cookies = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSessionRepositoryFilter", jakarta.servlet.Filter.class))
                .apply(springSecurity()).build();
        testSessions = new TestSessions(sessions);
        user = transactions.inTransaction(() -> users.create("session-" + UUID.randomUUID(),
                "session@example.com", UserStatus.ACTIVE, UserRole.ADMIN, Instant.now()));
    }

    @AfterEach
    void cleanUp() {
        cookies.forEach(cookie -> sessions.deleteById(testSessions.id(cookie)));
        jdbc.update("DELETE FROM users WHERE id = ?", user.id());
    }

    @Test
    void 로컬_설정의_발급_세션_쿠키에도_Domain이_없다() {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        cookieSerializer.writeCookieValue(new CookieSerializer.CookieValue(request, response, "test-session"));
        var cookie = response.getCookie("SESSION");
        assertNotNull(cookie);
        assertNull(cookie.getDomain());
        assertTrue(cookie.isHttpOnly());
        assertTrue(cookie.getSecure());
        assertEquals("Lax", cookie.getAttribute("SameSite"));
    }

    @Test
    void Flyway_스키마와_7일_만료와_ID만_저장하는_principal_인덱스() throws Exception {
        assertTrue(Arrays.stream(flyway.info().applied()).anyMatch(migration ->
                "V3__create_spring_session.sql".equals(migration.getScript())));
        try (var schema = getClass().getResourceAsStream("/org/springframework/session/jdbc/schema-postgresql.sql");
             var migration = getClass().getResourceAsStream("/db/migration/V3__create_spring_session.sql")) {
            assertNotNull(schema);
            assertNotNull(migration);
            assertArrayEquals(schema.readAllBytes(), migration.readAllBytes());
        }
        var cookie = session();
        var row = jdbc.queryForMap("SELECT principal_name, max_inactive_interval FROM spring_session WHERE session_id = ?",
                testSessions.id(cookie));
        assertEquals(Long.toString(user.id()), row.get("principal_name"));
        assertEquals(604800, row.get("max_inactive_interval"));
        mvc.perform(get("/api/me").cookie(cookie)).andExpect(status().isOk());
        org.springframework.session.Session stored = sessions.findById(testSessions.id(cookie));
        assertEquals(java.util.Set.of(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME),
                stored.getAttributeNames());
        assertEquals(Long.toString(user.id()), stored.getAttribute(
                FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME));
    }

    @Test
    void 로그인_없거나_모르는_쿠키는_JSON_401이고_리다이렉트하지_않는다() throws Exception {
        unauthorized(mvc.perform(get("/api/me")).andReturn());
        unauthorized(mvc.perform(get("/api/me").cookie(new Cookie("SESSION", "unknown"))).andReturn());
        unauthorized(mvc.perform(post("/api/missing")).andReturn());
    }

    @Test
    void 만료된_세션은_401이다() throws Exception {
        var cookie = session();
        jdbc.update("UPDATE spring_session SET last_access_time = 0, expiry_time = 0 WHERE session_id = ?",
                testSessions.id(cookie));
        unauthorized(mvc.perform(get("/api/me").cookie(cookie)).andReturn());
    }

    @Test
    void 현재_사용자_응답은_정해진_다섯_필드만_포함한다() throws Exception {
        var cookie = session();
        mvc.perform(get("/api/me").cookie(cookie)).andExpect(status().isOk())
                .andExpect(content().json("""
                        {"email":"session@example.com","status":"ACTIVE","role":"ADMIN",
                         "termsAgreed":false,"waitlistPosition":null}
                        """, org.springframework.test.json.JsonCompareMode.STRICT));
        jdbc.update("UPDATE users SET terms_agreed_at = now(), terms_version = 'v1' WHERE id = ?", user.id());
        mvc.perform(get("/api/me").cookie(cookie)).andExpect(jsonPath("$.termsAgreed").value(true));
    }

    @Test
    void 역할과_상태를_DB에서_매번_읽는다() throws Exception {
        var cookie = session();
        mvc.perform(get("/api/me").cookie(cookie)).andExpect(jsonPath("$.role").value("ADMIN"));
        jdbc.update("UPDATE users SET role = 'USER', status = 'WAITLISTED' WHERE id = ?", user.id());
        mvc.perform(get("/api/me").cookie(cookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.status").value("WAITLISTED"));
        jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", user.id());
        mvc.perform(get("/api/me").cookie(cookie)).andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void 사용자_삭제는_두_세션을_모두_401로_만들고_무효화한다() throws Exception {
        var first = session();
        var second = session();
        jdbc.update("DELETE FROM users WHERE id = ?", user.id());
        unauthorized(mvc.perform(get("/api/me").cookie(first)).andReturn());
        unauthorized(mvc.perform(get("/api/me").cookie(second)).andReturn());
        assertNull(sessions.findById(testSessions.id(first)));
        assertNull(sessions.findById(testSessions.id(second)));
    }

    @Test
    void 로그인한_사용자의_없는_API는_JSON_404다() throws Exception {
        mvc.perform(get("/api/does-not-exist").cookie(session())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.retryAfterSeconds").doesNotExist());
    }

    @Test
    void 실제_HTTP의_메서드_오류는_401이_아닌_JSON_405다() throws Exception {
        var cookie = session();
        var me = http("GET", "/api/me", cookie, null);
        assertEquals(200, me.statusCode());
        var token = me.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("XSRF-TOKEN="))
                .map(value -> value.substring("XSRF-TOKEN=".length(), value.indexOf(';')))
                .findFirst().orElseThrow();
        var response = http("POST", "/api/me", cookie, token);
        assertError(response, 405, "METHOD_NOT_ALLOWED");
        assertTrue(response.headers().firstValue("Allow").orElseThrow().contains("GET"));
        assertEquals(200, http("GET", "/api/me", cookie, null).statusCode());
    }

    @Test
    void 실제_컨테이너의_ERROR_디스패치도_400과_500을_JSON으로_유지한다() throws Exception {
        var cookie = session();
        assertError(http("GET", "/api/test-servlet-errors/bad-request", cookie, null), 400, "INVALID_REQUEST");
        assertError(http("GET", "/api/test-servlet-errors/failure", cookie, null), 500, "INTERNAL_ERROR");
        assertError(http("GET", "/api/does-not-exist", cookie, null), 404, "NOT_FOUND");
        assertError(http("GET", "/api/me", null, null), 401, "UNAUTHORIZED");
        assertEquals(200, http("GET", "/api/me", cookie, null).statusCode());
    }

    @Test
    void 실제_HTTP의_처리되지_않은_컨트롤러_예외도_JSON_500이다() throws Exception {
        var cookie = session();
        doThrow(new IllegalStateException("private controller failure")).when(meController).me(any(User.class));
        var response = http("GET", "/api/me", cookie, null);
        assertError(response, 500, "INTERNAL_ERROR");
        assertFalse(response.body().contains("private controller failure"));
        assertNotNull(sessions.findById(testSessions.id(cookie)));
    }

    private HttpResponse<String> http(String method, String path, Cookie session, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        var cookie = session == null ? "" : "SESSION=" + session.getValue();
        if (token != null) {
            cookie += "; XSRF-TOKEN=" + token;
            request.header("X-XSRF-TOKEN", token);
        }
        if (!cookie.isEmpty()) request.header("Cookie", cookie);
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private void assertError(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Location").isEmpty());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
        var body = mapper.readTree(response.body());
        assertEquals(2, body.size(), response.body());
        assertEquals(code, body.get("code").asText());
        assertTrue(body.get("message").isString());
        assertFalse(response.body().contains("private servlet failure"));
    }

    // 명시적으로 가져온 이 컨텍스트에서만 오류를 주입한다. 테스트 컨트롤러를 스캔에 넣지 않는다.
    @TestConfiguration(proxyBeanMethods = false)
    static class ServletErrors {
        @Bean
        FilterRegistrationBean<Filter> servletErrors() {
            Filter filter = (request, response, chain) -> {
                var path = ((HttpServletRequest) request).getRequestURI();
                if (path.endsWith("/bad-request")) {
                    ((HttpServletResponse) response).sendError(400, "private servlet failure");
                } else {
                    throw new ServletException("private servlet failure");
                }
            };
            var registration = new FilterRegistrationBean<>(filter);
            registration.addUrlPatterns("/api/test-servlet-errors/*");
            registration.setDispatcherTypes(DispatcherType.REQUEST);
            return registration;
        }
    }

    @Test
    void 원본_CSRF_쿠키로_로그아웃하면_그_세션만_삭제하고_쿠키를_만료시킨다() throws Exception {
        var first = session();
        var second = session();
        var token = csrfCookie(first);
        var response = mvc.perform(post("/api/auth/logout").cookie(first, token)
                        .header("X-XSRF-TOKEN", token.getValue()))
                .andExpect(status().isNoContent()).andExpect(content().string(""))
                .andExpect(header().doesNotExist("Location")).andReturn().getResponse();
        assertNull(sessions.findById(testSessions.id(first)));
        var expired = response.getCookie("SESSION");
        assertNotNull(expired);
        assertEquals(0, expired.getMaxAge());
        assertAll("SESSION 만료 쿠키: " + response.getHeaders("Set-Cookie"),
                () -> assertTrue(expired.isHttpOnly()),
                () -> assertTrue(expired.getSecure()),
                () -> assertEquals("Lax", expired.getAttribute("SameSite")),
                () -> assertEquals("/", expired.getPath()),
                () -> assertNull(expired.getDomain()));
        unauthorized(mvc.perform(get("/api/me").cookie(first)).andReturn());
        mvc.perform(get("/api/me").cookie(second)).andExpect(status().isOk());
    }

    @Test
    void CSRF_헤더가_없거나_다르면_403이며_세션을_유지한다() throws Exception {
        var cookie = session();
        var token = csrfCookie(cookie);
        mvc.perform(post("/api/auth/logout").cookie(cookie, token)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"))
                .andExpect(jsonPath("$.message").isString());
        mvc.perform(post("/api/auth/logout").cookie(cookie, token).header("X-XSRF-TOKEN", "different"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        assertNotNull(sessions.findById(testSessions.id(cookie)));
        mvc.perform(get("/api/me").cookie(cookie)).andExpect(status().isOk());
    }

    @Test
    void 세션_없는_로그아웃도_CSRF를_검사하고_유효하면_204다() throws Exception {
        mvc.perform(post("/api/auth/logout")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        var token = csrfCookie(null);
        mvc.perform(post("/api/auth/logout").cookie(token).header("X-XSRF-TOKEN", token.getValue()))
                .andExpect(status().isNoContent()).andExpect(header().doesNotExist("Location"));
        mvc.perform(post("/api/auth/logout").cookie(new Cookie("SESSION", "unknown"), token)
                .header("X-XSRF-TOKEN", token.getValue())).andExpect(status().isNoContent());
    }

    private Cookie session() {
        var cookie = testSessions.create(user.id());
        cookies.add(cookie);
        return cookie;
    }

    private Cookie csrfCookie(Cookie session) throws Exception {
        var request = get("/api/me");
        if (session != null) request.cookie(session);
        var response = mvc.perform(request).andReturn().getResponse();
        var token = response.getCookie("XSRF-TOKEN");
        assertNotNull(token);
        assertFalse(token.isHttpOnly());
        assertTrue(token.getSecure());
        assertEquals("Lax", token.getAttribute("SameSite"));
        assertEquals("/", token.getPath());
        assertNull(token.getDomain());
        return token;
    }

    private void unauthorized(MvcResult result) throws Exception {
        assertEquals(401, result.getResponse().getStatus());
        assertNull(result.getResponse().getHeader("Location"));
        content().contentTypeCompatibleWith("application/json").match(result);
        jsonPath("$.code").value("UNAUTHORIZED").match(result);
        jsonPath("$.message").isString().match(result);
        jsonPath("$.retryAfterSeconds").doesNotExist().match(result);
    }
}
