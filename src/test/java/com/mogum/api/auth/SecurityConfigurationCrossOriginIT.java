package com.mogum.api.auth;

import com.mogum.core.TransactionRunner;
import com.mogum.core.account.User;
import com.mogum.core.account.UserRepository;
import com.mogum.core.account.UserRole;
import com.mogum.core.account.UserStatus;
import com.mogum.support.PostgresTestConfiguration;
import com.mogum.support.TestSessions;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "app.web.origin=https://mogum.example",
        "app.web.cookie-domain=mogum.example"
})
class SecurityConfigurationCrossOriginIT extends PostgresTestConfiguration {
    private static final String ORIGIN = "https://mogum.example";
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcIndexedSessionRepository sessions;
    @Autowired private UserRepository users;
    @Autowired private TransactionRunner transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CookieSerializer cookieSerializer;
    private MockMvc mvc;
    private TestSessions testSessions;
    private User user;
    private Cookie session;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSessionRepositoryFilter", jakarta.servlet.Filter.class))
                .apply(springSecurity()).build();
        testSessions = new TestSessions(sessions);
        user = transactions.inTransaction(() -> users.create("cors-" + UUID.randomUUID(),
                "cors@example.com", UserStatus.ACTIVE, UserRole.ADMIN, Instant.now()));
        session = testSessions.create(user.id());
    }

    @AfterEach
    void cleanUp() {
        sessions.deleteById(testSessions.id(session));
        jdbc.update("DELETE FROM users WHERE id = ?", user.id());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "PUT", "PATCH", "DELETE"})
    void 허용_출처의_preflight는_세션과_CSRF_없이_응답한다(String method) throws Exception {
        mvc.perform(preflight(ORIGIN, method))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                .andExpect(header().string("Access-Control-Allow-Headers", "X-XSRF-TOKEN, Content-Type"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example", "https://mogum-git-x.vercel.app",
            "https://other.mogum.example"})
    void 다른_출처의_preflight는_거절한다(String origin) throws Exception {
        mvc.perform(preflight(origin, "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void 허용하지_않은_메서드와_헤더의_preflight도_거절한다() throws Exception {
        mvc.perform(preflight(ORIGIN, "TRACE")).andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(preflight(ORIGIN, "POST", "X-Other"))
                .andExpect(status().isForbidden());
    }

    @Test
    void CORS는_API_경로에만_적용한다() throws Exception {
        mvc.perform(options("/auth/google").secure(true).header("Origin", ORIGIN)
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void 허용_출처의_세션_GET은_CORS_헤더와_등록_도메인의_CSRF_쿠키를_준다() throws Exception {
        csrfCookie();
    }

    @Test
    void 허용_출처의_로그아웃은_쿠키와_헤더가_같으면_204이며_만료_속성을_유지한다() throws Exception {
        var token = csrfCookie();
        var response = mvc.perform(api(post("/api/auth/logout")).header("Origin", ORIGIN).cookie(session, token)
                        .header("X-XSRF-TOKEN", token.getValue()))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                .andReturn().getResponse();
        assertNull(sessions.findById(testSessions.id(session)));
        assertCookie(response.getCookie("SESSION"), true, null);
        assertEquals(0, response.getCookie("SESSION").getMaxAge());
        assertCookie(response.getCookie("XSRF-TOKEN"), false, "mogum.example");
        assertEquals(0, response.getCookie("XSRF-TOKEN").getMaxAge());
    }

    @Test
    void 허용_출처라도_CSRF_헤더가_없으면_JSON_403이다() throws Exception {
        var token = csrfCookie();
        mvc.perform(api(post("/api/auth/logout")).header("Origin", ORIGIN).cookie(session, token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"))
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        assertNotNull(sessions.findById(testSessions.id(session)));
    }

    @Test
    void 다른_출처의_실제_세션_요청도_거절한다() throws Exception {
        mvc.perform(api(get("/api/me")).cookie(session).header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void 등록_도메인을_설정해도_발급_세션_쿠키는_API_호스트에만_둔다() {
        var request = new MockHttpServletRequest();
        request.setServerName("api.mogum.example");
        request.setSecure(true);
        var response = new MockHttpServletResponse();
        cookieSerializer.writeCookieValue(new CookieSerializer.CookieValue(request, response, session.getValue()));
        assertCookie(response.getCookie("SESSION"), true, null);
    }

    private Cookie csrfCookie() throws Exception {
        var response = mvc.perform(api(get("/api/me")).header("Origin", ORIGIN).cookie(session))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                .andReturn().getResponse();
        var token = response.getCookie("XSRF-TOKEN");
        assertCookie(token, false, "mogum.example");
        return token;
    }

    private static void assertCookie(Cookie cookie, boolean httpOnly, String domain) {
        assertNotNull(cookie);
        assertEquals(httpOnly, cookie.isHttpOnly());
        assertTrue(cookie.getSecure());
        assertEquals("Lax", cookie.getAttribute("SameSite"));
        assertEquals("/", cookie.getPath());
        assertEquals(domain, cookie.getDomain());
    }

    private static MockHttpServletRequestBuilder api(MockHttpServletRequestBuilder request) {
        return request.secure(true).with(value -> {
            value.setServerName("api.mogum.example");
            return value;
        });
    }

    private static MockHttpServletRequestBuilder preflight(String origin, String method) {
        return preflight(origin, method, "X-XSRF-TOKEN, Content-Type");
    }

    private static MockHttpServletRequestBuilder preflight(String origin, String method, String headers) {
        return api(options("/api/me")).header("Origin", origin)
                .header("Access-Control-Request-Method", method)
                .header("Access-Control-Request-Headers", headers);
    }
}
