package com.mogum.common.logging;

import com.mogum.account.domain.User;
import com.mogum.account.domain.UserRole;
import com.mogum.account.repository.UserRepository;
import com.mogum.common.api.ApiResponse;
import com.mogum.support.PostgresTestConfiguration;
import com.mogum.support.TestSessions;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.servlet.AbstractFilterRegistrationBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.tomcat.threads.max=1", "server.tomcat.threads.min-spare=1"})
@Import({RequestLogFilterIT.FailureController.class, RequestLogFilterIT.ErrorDispatchProbe.class,
        RequestLogFilterIT.FilterFailure.class})
@ExtendWith(OutputCaptureExtension.class)
class RequestLogFilterIT extends PostgresTestConfiguration {
    @Autowired private UserRepository users;
    @Autowired private JdbcIndexedSessionRepository sessions;
    @Autowired private ApplicationContext context;
    @Value("${local.server.port}") private int port;
    private User user;
    private Cookie session;
    private TestSessions testSessions;
    private HttpClient client;

    @BeforeEach
    void setUp() {
        var key = "request-log-" + UUID.randomUUID();
        user = users.save(User.join("google", key, key + "@example.com", UserRole.ADMIN));
        testSessions = new TestSessions(sessions);
        session = testSessions.create(user.getId());
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void cleanUp() {
        if (client != null) client.close();
        if (session != null) sessions.deleteById(testSessions.id(session));
        if (user != null) users.deleteById(user.getId());
    }

    @Test
    void 인증과_CSRF와_MVC_오류마다_받은_상태로_한줄을_남긴다(CapturedOutput output) throws Exception {
        assertAccess(send("GET", "/api/me", false, null), 401, "GET", "/api/me", false, output);
        var me = send("GET", "/api/me", true, null);
        assertAccess(me, 200, "GET", "/api/me", true, output);
        assertAccess(send("POST", "/api/auth/logout", true, null), 403, "POST", "/api/auth/logout", true, output);
        assertAccess(send("GET", "/api/log-test-missing", true, null), 404, "GET", "/api/log-test-missing", true, output);
        var token = me.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("XSRF-TOKEN="))
                .map(value -> value.substring("XSRF-TOKEN=".length(), value.indexOf(';')))
                .findFirst().orElseThrow();
        assertAccess(send("POST", "/api/me", true, token), 405, "POST", "/api/me", true, output);
    }

    @Test
    void 필터_예외의_컨테이너_ERROR에_ID를_남기고_다음_요청에_회원_ID가_남지_않는다(CapturedOutput output) throws Exception {
        var response = send("GET", "/api/test-request-log-filter-failure", true, null);
        var failed = assertAccess(response, 500, "GET", "/api/test-request-log-filter-failure", true, output);
        var errors = output.getOut().lines().filter(line -> line.contains(" ERROR ")
                && line.contains("Servlet.service() for servlet [dispatcherServlet] in context with path [] threw exception")
                && line.contains("requestId=" + requestId(response))).toList();
        assertEquals(1, errors.size(), output.getAll());
        assertTrue(errors.getFirst().contains("userId=" + user.getId()), errors.getFirst());
        assertTrue(output.getOut().contains("java.lang.RuntimeException: request log filter failure marker"));
        var next = send("GET", "/api/me", false, null);
        var anonymous = assertAccess(next, 401, "GET", "/api/me", false, output);
        assertEquals(thread(failed), thread(anonymous));
        assertNotEquals(requestId(response), requestId(next));
    }

    @Test
    void 처리되지_않은_예외의_ERROR와_접근줄은_같은_ID를_쓴다(CapturedOutput output) throws Exception {
        var response = send("GET", "/api/test-request-log-failure", true, null);
        assertAccess(response, 500, "GET", "/api/test-request-log-failure", true, output);
        var error = output.getOut().lines().filter(line -> line.contains("Unhandled API exception")
                && line.contains("requestId=" + requestId(response))).toList();
        assertEquals(1, error.size());
        assertTrue(error.getFirst().contains("ERROR"));
        assertTrue(output.getOut().contains("java.lang.IllegalStateException: request log test failure"));
    }

    @Test
    void sendError의_ERROR_디스패치도_같은_ID와_404_한줄이다(CapturedOutput output) throws Exception {
        var response = send("GET", "/auth/unknown", false, null);
        assertAccess(response, 404, "GET", "/auth/unknown", false, output);
        var dispatch = output.getOut().lines().filter(line -> line.contains("requestId=" + requestId(response))
                && line.contains("ERROR dispatch probe")).toList();
        assertEquals(1, dispatch.size());
    }

    @Test
    void 같은_서버_스레드의_다음_비로그인_요청에_회원_ID가_남지_않는다(CapturedOutput output) throws Exception {
        var loggedIn = assertAccess(send("GET", "/api/me", true, null), 200, "GET", "/api/me", true, output);
        var ids = new HashSet<String>();
        for (int i = 0; i < 8; i++) {
            var response = send("GET", "/api/me", false, null);
            var anonymous = assertAccess(response, 401, "GET", "/api/me", false, output);
            assertEquals(thread(loggedIn), thread(anonymous));
            assertTrue(ids.add(requestId(response)));
        }
    }

    @Test
    void 외부_요청_ID와_OAuth_쿼리와_다른_입력은_로그에_남지_않는다(CapturedOutput output) throws Exception {
        var marker = "private-marker-" + UUID.randomUUID();
        var response = client.send(HttpRequest.newBuilder(uri("/auth/google/callback?code=" + marker + "&state=" + marker))
                .header("X-Request-Id", marker).header("X-Private-Test", marker)
                .header("Cookie", "PRIVATE_TEST=" + marker).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertAccess(response, 302, "GET", "/auth/google/callback", false, output);
        assertNotEquals(marker, requestId(response));
        assertFalse(output.getAll().contains(marker));
    }

    @Test
    void 헬스는_ID를_주지만_접근줄은_없다(CapturedOutput output) throws Exception {
        var response = send("GET", "/actuator/health", false, null);
        assertEquals(200, response.statusCode());
        UUID.fromString(requestId(response));
        // 같은 단일 서버 스레드의 다음 접근 줄을 기다리면 헬스 종료 처리도 끝났다.
        assertAccess(send("GET", "/api/me", false, null), 401, "GET", "/api/me", false, output);
        assertTrue(accessLines(output, requestId(response)).isEmpty());
    }

    @Test
    void 등록된_순서는_세션과_보안보다_바깥이며_ERROR도_포함한다() {
        var logging = context.getBean("requestLogFilter", FilterRegistrationBean.class);
        var sessionFilter = context.getBean("sessionRepositoryFilterRegistration", AbstractFilterRegistrationBean.class);
        var security = context.getBean("securityFilterChainRegistration", AbstractFilterRegistrationBean.class);
        assertEquals(Ordered.HIGHEST_PRECEDENCE, logging.getOrder());
        assertTrue(logging.getOrder() < sessionFilter.getOrder());
        assertTrue(logging.getOrder() < security.getOrder());
        assertTrue(logging.determineDispatcherTypes().contains(DispatcherType.ERROR));
        context.getBeansOfType(AbstractFilterRegistrationBean.class).values()
                .forEach(filter -> assertTrue(logging.getOrder() <= filter.getOrder()));
        var server = (TomcatWebServer) ((WebServerApplicationContext) context).getWebServer();
        var servletContext = (org.apache.catalina.Context) server.getTomcat().getHost().findChildren()[0];
        // 같은 최솟값을 쓰는 Boot 필터도 있으므로 실제 컨테이너 매핑의 첫 자리까지 확인한다.
        assertEquals(logging.getFilterName(), servletContext.findFilterMaps()[0].getFilterName());
    }

    private HttpResponse<String> send(String method, String path, boolean authenticated, String token) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10));
        if (authenticated) builder.header("Cookie", "SESSION=" + session.getValue()
                + (token == null ? "" : "; XSRF-TOKEN=" + token));
        if (token != null) builder.header("X-XSRF-TOKEN", token);
        return client.send(builder.method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private String assertAccess(HttpResponse<String> response, int status, String method, String path,
                                boolean authenticated, CapturedOutput output) throws InterruptedException {
        assertEquals(status, response.statusCode(), response.body());
        var id = requestId(response);
        assertEquals(id, UUID.fromString(id).toString());
        var deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (accessLines(output, id).isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
        var lines = accessLines(output, id);
        assertEquals(1, lines.size(), output.getAll());
        var line = lines.getFirst();
        assertTrue(line.contains(" INFO "), line);
        assertTrue(line.contains("access method=" + method + " path=" + path + " status=" + status + " durationMs="), line);
        assertTrue(line.matches(".*durationMs=\\d+$"), line);
        assertEquals("UTC", TimeZone.getDefault().getID());
        assertTrue(line.matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\+09:00 .*"), line);
        if (authenticated) assertTrue(line.contains("userId=" + user.getId()), line);
        else assertFalse(line.contains("userId="), line);
        assertFalse(response.body().contains("requestId"));
        assertFalse(response.headers().firstValue("Access-Control-Expose-Headers").orElse("").contains("X-Request-Id"));
        return line;
    }

    private static List<String> accessLines(CapturedOutput output, String id) {
        return output.getOut().lines().filter(line -> line.contains("requestId=" + id) && line.contains("access method=")).toList();
    }

    private static String requestId(HttpResponse<?> response) {
        var values = response.headers().allValues("X-Request-Id");
        assertEquals(1, values.size());
        return values.getFirst();
    }

    private static String thread(String line) {
        return line.substring(line.lastIndexOf('['), line.lastIndexOf(']') + 1);
    }

    @RestController
    static class FailureController {
        @GetMapping("/api/test-request-log-failure")
        public ApiResponse<FailureResponse> fail() {
            throw new IllegalStateException("request log test failure");
        }
    }

    record FailureResponse(String value) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class FilterFailure {
        @Bean
        FilterRegistrationBean<Filter> filterFailure(
                @Qualifier("securityFilterChainRegistration") AbstractFilterRegistrationBean<?> security) {
            Filter failure = (request, response, chain) -> {
                if ("/api/test-request-log-filter-failure".equals(((HttpServletRequest) request).getRequestURI())) {
                    throw new RuntimeException("request log filter failure marker");
                }
                chain.doFilter(request, response);
            };
            var registration = new FilterRegistrationBean<>(failure);
            registration.setOrder(security.getOrder() + 1);
            registration.setDispatcherTypes(DispatcherType.REQUEST);
            return registration;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ErrorDispatchProbe {
        @Bean
        FilterRegistrationBean<Filter> errorDispatchProbe() {
            Filter probe = (request, response, chain) -> {
                LoggerFactory.getLogger(ErrorDispatchProbe.class).info("ERROR dispatch probe");
                chain.doFilter(request, response);
            };
            var registration = new FilterRegistrationBean<>(probe);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
            registration.setDispatcherTypes(DispatcherType.ERROR);
            return registration;
        }
    }
}
