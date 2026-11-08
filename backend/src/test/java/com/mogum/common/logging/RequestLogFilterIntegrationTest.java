package com.mogum.common.logging;

import com.mogum.account.domain.User;
import com.mogum.account.repository.UserRepository;
import com.mogum.support.TestSessions;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.servlet.AbstractFilterRegistrationBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

import com.mogum.support.IntegrationTest;
import com.mogum.support.TestRequests;
import com.mogum.account.domain.UserFixtures;
import org.junit.jupiter.api.DisplayName;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class RequestLogFilterIntegrationTest {
    @Autowired private UserRepository users;
    @Autowired private JdbcIndexedSessionRepository sessions;
    @Autowired private ApplicationContext context;
    @Autowired private TestRequests requests;
    private User user;
    private Cookie session;

    @BeforeEach
    void prepareSession() {
        user = users.save(UserFixtures.user("request-log@example.com"));
        session = new TestSessions(sessions).create(user.getId());
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0} {1} 인증 {2} 상태 {3}")
    @org.junit.jupiter.params.provider.CsvSource({"GET,/api/me,false,401,false", "GET,/api/me,true,200,false",
            "POST,/api/auth/logout,true,403,false", "GET,/api/log-test-missing,true,404,false", "POST,/api/me,true,405,true"})
    @DisplayName("인증 CSRF MVC 오류는 최종 상태로 접근 줄 하나를 남긴다")
    void logsFinalStatusOnce(String method, String path, boolean authenticated, int status, boolean csrf, CapturedOutput output) throws Exception {
        // given
        String token = csrf ? send("GET", "/api/me", true, null).getResponseCookies().getFirst("XSRF-TOKEN").getValue() : null;
        // when
        var response = send(method, path, authenticated, token);
        // then
        assertAccess(response, status, method, path, authenticated, output);
    }

    @Test
    @DisplayName("필터 예외의 컨테이너 ERROR에 요청 ID와 회원 ID와 스택을 남긴다")
    void cleansUserMdcAfterFilterFailure(CapturedOutput output) throws Exception {
        // when
        var response = send("GET", "/api/test-request-log-filter-failure", true, null);
        // then
        assertAccess(response, 500, "GET", "/api/test-request-log-filter-failure", true, output);
        var errors = output.getOut().lines().filter(line -> line.contains(" ERROR ")
                && line.contains("Servlet.service() for servlet [dispatcherServlet] in context with path [] threw exception")
                && line.contains("requestId=" + requestId(response))).toList();
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst().contains("userId=" + user.getId())).isTrue();
        assertThat(output.getOut().contains("java.lang.RuntimeException: request log filter failure marker")).isTrue();

    }

    @Test
    @DisplayName("필터 예외 뒤 같은 스레드의 다음 비로그인 요청에 회원 MDC가 남지 않는다")
    void cleansUserMdcForAnonymousRequestAfterFailure(CapturedOutput output) throws Exception {
        // given
        var failedResponse = send("GET", "/api/test-request-log-filter-failure", true, null);
        var failed = assertAccess(failedResponse, 500, "GET", "/api/test-request-log-filter-failure", true, output);
        // when
        var response = send("GET", "/api/me", false, null);
        // then
        var anonymous = assertAccess(response, 401, "GET", "/api/me", false, output);
        assertThat(thread(anonymous)).isEqualTo(thread(failed));
        assertThat(requestId(response)).isNotEqualTo(requestId(failedResponse));
    }

    @Test
    @DisplayName("처리되지 않은 예외의 ERROR와 접근줄은 같은 ID를 쓴다")
    void correlatesUnhandledExceptionAndAccessLog(CapturedOutput output) throws Exception {
        // when
        var response = send("GET", "/api/test-request-log-failure", true, null);
        // then
        assertAccess(response, 500, "GET", "/api/test-request-log-failure", true, output);
        var error = output.getOut().lines().filter(line -> line.contains("Unhandled API exception")
                && line.contains("requestId=" + requestId(response))).toList();
        assertThat(error).hasSize(1);
        assertThat(error.getFirst().contains("ERROR")).isTrue();
        assertThat(output.getOut().contains("java.lang.IllegalStateException: request log test failure")).isTrue();
    }

    @Test
    @DisplayName("sendError의 ERROR 디스패치도 같은 ID와 404 한줄이다")
    void correlatesErrorDispatchAndAccessLog(CapturedOutput output) throws Exception {
        // when
        var response = send("GET", "/auth/unknown", false, null);
        // then
        assertAccess(response, 404, "GET", "/auth/unknown", false, output);
        assertThat(response.getResponseHeaders().getOrEmpty("X-Test-Dispatch")).containsExactly("ERROR");
        assertThat(response.getResponseHeaders().getOrEmpty("X-Test-Dispatch-Request-Id"))
                .containsExactly(requestId(response));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "연속 비로그인 요청 {0}")
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    @DisplayName("같은 서버 스레드에서 연속 비로그인 요청에도 회원 MDC가 남지 않는다")
    void cleansUserMdcForNextAnonymousRequests(int sequence, CapturedOutput output) throws Exception {
        // given
        var loggedInResponse = send("GET", "/api/me", true, null);
        var loggedIn = assertAccess(loggedInResponse, 200, "GET", "/api/me", true, output);
        var ids = new HashSet<String>();
        ids.add(requestId(loggedInResponse));
        for (int i = 1; i < sequence; i++) {
            var earlier = send("GET", "/api/me", false, null);
            var line = assertAccess(earlier, 401, "GET", "/api/me", false, output);
            assertThat(thread(line)).isEqualTo(thread(loggedIn));
            assertThat(ids.add(requestId(earlier))).isTrue();
        }
        // when
        var response = send("GET", "/api/me", false, null);
        // then
        var anonymous = assertAccess(response, 401, "GET", "/api/me", false, output);
        assertThat(thread(anonymous)).isEqualTo(thread(loggedIn));
        assertThat(ids).doesNotContain(requestId(response));
    }

    @Test
    @DisplayName("외부 요청 ID와 OAuth 쿼리와 다른 입력은 로그에 남지 않는다")
    void omitsUntrustedHeadersCookiesAndOAuthQuery(CapturedOutput output) throws Exception {
        // given
        var marker = "private-marker-fixed";
        // when
        var response = requests.anonymous().get().uri("/auth/google/callback?code=" + marker + "&state=" + marker)
                .header("X-Request-Id", marker).header("X-Private-Test", marker)
                .header("Cookie", "PRIVATE_TEST=" + marker).exchange().returnResult(String.class);
        // then
        assertAccess(response, 302, "GET", "/auth/google/callback", false, output);
        assertThat(requestId(response)).isNotEqualTo(marker);
        assertThat(output.getAll().contains(marker)).isFalse();
    }

    @Test
    @DisplayName("헬스는 ID를 주지만 접근줄은 없다")
    void omitsHealthAccessLogButReturnsId(CapturedOutput output) throws Exception {
        // when
        var response = send("GET", "/actuator/health", false, null);
        // then
        assertThat(response.getStatus().value()).isEqualTo(200);
        UUID.fromString(requestId(response));
        // 같은 단일 서버 스레드의 다음 접근 줄을 기다리면 헬스 종료 처리도 끝났다.
        assertAccess(send("GET", "/api/me", false, null), 401, "GET", "/api/me", false, output);
        assertThat(accessLines(output, requestId(response)).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("등록된 순서는 세션과 보안보다 바깥이며 ERROR도 포함한다")
    void registersLoggingOutsideSecurityAndSession() {
        // given
        var sessionFilter = context.getBean("sessionRepositoryFilterRegistration", AbstractFilterRegistrationBean.class);
        var security = context.getBean("securityFilterChainRegistration", AbstractFilterRegistrationBean.class);
        // when
        var logging = context.getBean("requestLogFilter", FilterRegistrationBean.class);
        // then
        assertThat(logging.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(logging.getOrder() < sessionFilter.getOrder()).isTrue();
        assertThat(logging.getOrder() < security.getOrder()).isTrue();
        assertThat(logging.determineDispatcherTypes().contains(DispatcherType.ERROR)).isTrue();
        context.getBeansOfType(AbstractFilterRegistrationBean.class).values()
                .forEach(filter -> assertThat(logging.getOrder() <= filter.getOrder()).isTrue());
        var server = (TomcatWebServer) ((WebServerApplicationContext) context).getWebServer();
        var servletContext = (org.apache.catalina.Context) server.getTomcat().getHost().findChildren()[0];
        // 같은 최솟값을 쓰는 Boot 필터도 있으므로 실제 컨테이너 매핑의 첫 자리까지 확인한다.
        assertThat(servletContext.findFilterMaps()[0].getFilterName()).isEqualTo(logging.getFilterName());
    }

    private EntityExchangeResult<String> send(String method, String path, boolean authenticated, String token) {
        var builder = requests.anonymous().method(org.springframework.http.HttpMethod.valueOf(method)).uri(path);
        if (authenticated) builder.cookie("SESSION", session.getValue());
        if (token != null) builder.cookie("XSRF-TOKEN", token).header("X-XSRF-TOKEN", token);
        return builder.exchange().returnResult(String.class);
    }

    private String assertAccess(EntityExchangeResult<String> response, int status, String method, String path,
                                boolean authenticated, CapturedOutput output) throws InterruptedException {
        assertThat(response.getStatus().value()).isEqualTo(status);
        var id = requestId(response);
        assertThat(UUID.fromString(id).toString()).isEqualTo(id);
        var deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (accessLines(output, id).isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
        var lines = accessLines(output, id);
        assertThat(lines).hasSize(1);
        var line = lines.getFirst();
        assertThat(line).contains(" INFO ");
        assertThat(line).contains("access method=" + method + " path=" + path + " status=" + status + " durationMs=");
        assertThat(line.matches(".*durationMs=\\d+$")).isTrue();
        assertThat(TimeZone.getDefault().getID()).isEqualTo("UTC");
        assertThat(line.matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\+09:00 .*")).isTrue();
        if (authenticated) assertThat(line).contains("userId=" + user.getId());
        else assertThat(line).doesNotContain("userId=");
        assertThat(java.util.Optional.ofNullable(response.getResponseBody()).orElse("")).doesNotContain("requestId");
        assertThat(java.util.Optional.ofNullable(response.getResponseHeaders().getFirst("Access-Control-Expose-Headers")).orElse("").contains("X-Request-Id")).isFalse();
        return line;
    }

    private static List<String> accessLines(CapturedOutput output, String id) {
        return output.getOut().lines().filter(line -> line.contains("requestId=" + id) && line.contains("access method=")).toList();
    }

    private static String requestId(EntityExchangeResult<?> response) {
        var values = response.getResponseHeaders().getOrEmpty("X-Request-Id");
        assertThat(values).hasSize(1);
        return values.getFirst();
    }

    private static String thread(String line) {
        return line.substring(line.lastIndexOf('['), line.lastIndexOf(']') + 1);
    }

}
