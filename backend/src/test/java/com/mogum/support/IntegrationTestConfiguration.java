package com.mogum.support;

import com.mogum.common.api.ApiResponse;
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.common.logging.RequestLogFilter;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.web.servlet.AbstractFilterRegistrationBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** 전용 경로의 검증/실패 빈은 모든 통합 테스트가 같은 구성으로 쓴다. */
@TestConfiguration(proxyBeanMethods = false)
@Import({IntegrationTestConfiguration.ValidationController.class,
        IntegrationTestConfiguration.FailureController.class,
        IntegrationTestConfiguration.ServletErrors.class,
        IntegrationTestConfiguration.FilterFailure.class,
        IntegrationTestConfiguration.ErrorDispatchProbe.class,
        IntegrationTestConfiguration.DomainFailureController.class})
public class IntegrationTestConfiguration {
    // 바깥 설정을 파싱할 때 단위 테스트 설정까지 자동 등록하지 않도록 비컴포넌트 안에 둔다.
    public static final class CookieUnitSupport {
        /** 세션 직렬화기 구성만 보는 단위 테스트용이며 공용 앱에는 가져오지 않는다. */
        @TestConfiguration(proxyBeanMethods = false)
        @org.springframework.boot.context.properties.EnableConfigurationProperties(com.mogum.config.WebProperties.class)
        public static class SessionCookieConfiguration {
            @Bean
            public org.springframework.session.web.http.CookieSerializer cookieSerializer() {
                var serializer = new org.springframework.session.web.http.DefaultCookieSerializer();
                new com.mogum.auth.SecurityConfiguration().sessionCookieSecurity().customize(serializer);
                return serializer;
            }
        }
    }

    @Bean
    public TestRequests testRequests(RestTestClient client, JdbcIndexedSessionRepository sessions) {
        return new TestRequests(client, sessions);
    }

    /** 스파이 대신 연결 경계에서 실패를 주입한다. 각 검사는 finally에서 복구한다. */
    @Bean
    public static BeanPostProcessor databaseProbe() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                return bean instanceof DataSource source ? new DatabaseProbe(source) : bean;
            }
        };
    }

    public static final class DatabaseProbe extends DelegatingDataSource {
        private volatile boolean failing;
        private final AtomicLong attempts = new AtomicLong();

        public DatabaseProbe(DataSource source) { super(source); }
        public void failConnections(boolean value) { failing = value; }
        public long connectionAttempts() { return attempts.get(); }

        @Override
        public Connection getConnection() throws SQLException {
            beforeConnection();
            return super.getConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            beforeConnection();
            return super.getConnection(username, password);
        }

        private void beforeConnection() throws SQLException {
            attempts.incrementAndGet();
            if (failing) throw new SQLException("test database unavailable");
        }
    }

    /** Google 루프백 서버도 컨텍스트 키를 바꾸지 않도록 공용 initializer에서 연결한다. */
    public static final class OAuthInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            TestPropertyValues.of(
                    "spring.security.oauth2.client.provider.google.authorization-uri=" + FakeGoogle.ORIGIN + "/authorize",
                    "spring.security.oauth2.client.provider.google.token-uri=" + FakeGoogle.ORIGIN + "/token",
                    "spring.security.oauth2.client.provider.google.user-info-uri=" + FakeGoogle.ORIGIN + "/userinfo")
                    .applyTo(context);
        }
    }

    /** 실제 토큰 엔드포인트처럼 verifier와 S256 challenge를 대조한다. JVM 전체에서 공유한다. */
    public static final class FakeGoogle {
        public static final AtomicInteger TOKEN_CALLS = new AtomicInteger();
        public static final AtomicReference<String> CODE_CHALLENGE = new AtomicReference<>();
        public static final HttpServer GOOGLE = fakeGoogle();
        public static final String ORIGIN = "http://127.0.0.1:" + GOOGLE.getAddress().getPort();
        public static volatile String sub;
        public static volatile String email;
        public static volatile boolean verified;
        public static volatile boolean tokenFailure;
        public static volatile boolean userInfoFailure;
        private static HttpServer fakeGoogle() {
            try {
                var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/token", exchange -> {
                    TOKEN_CALLS.incrementAndGet();
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    var form = UriComponentsBuilder.fromUriString("http://localhost/?" + body).build().getQueryParams();
                    String verifier = form.getFirst("code_verifier");
                    if (verifier != null) verifier = java.net.URLDecoder.decode(verifier, StandardCharsets.UTF_8);
                    boolean bad = tokenFailure || "bad-token".equals(form.getFirst("code")) || verifier == null
                            || CODE_CHALLENGE.get() == null || !CODE_CHALLENGE.get().equals(challenge(verifier));
                    String response = bad ? "{\"error\":\"invalid_grant\",\"error_description\":\"" + form.getFirst("code") + "\"}" : "{\"access_token\":\"fake-access-token\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"id_token\":\"fake-id-token\"}";
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(bad ? 400 : 200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
                server.createContext("/userinfo", exchange -> {
                    String response = userInfoFailure ? "{\"error\":\"invalid_token\",\"error_description\":\"" + sub + "\"}"
                            : "{\"sub\":\"" + sub + "\",\"email\":\"" + email + "\",\"email_verified\":" + verified + "}";
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(userInfoFailure ? 400 : 200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
                Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0), "test-google-shutdown"));
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
    }

    @RestController
    public static class ValidationController {
        @PostMapping("/api/test-request-validation")
        public ApiResponse<ValidationRequest> validate(@Valid @RequestBody ValidationRequest request) {
            return ApiResponse.of(request);
        }

        @PostMapping("/api/test-request-validation-other")
        public ApiResponse<OtherValidationRequest> validateOther(@Valid @RequestBody OtherValidationRequest request) {
            return ApiResponse.of(request);
        }
    }

    record ValidationRequest(@NotBlank @Size(max = 5) String topic,
                             @NotBlank @Size(max = 10) String purpose) { }

    record OtherValidationRequest(@Size(min = 2, max = 5) String topic,
                                  @Pattern(regexp = "학습") String purpose,
                                  @NotNull String requestId) { }

    @TestConfiguration(proxyBeanMethods = false)
    public static class ServletErrors {
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

    @RestController
    public static class FailureController {
        @GetMapping("/api/test-request-log-failure")
        public ApiResponse<FailureResponse> fail() {
            throw new IllegalStateException("request log test failure");
        }
    }

    record FailureResponse(String value) { }

    @TestConfiguration(proxyBeanMethods = false)
    public static class FilterFailure {
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
    public static class ErrorDispatchProbe {
        @Bean
        FilterRegistrationBean<Filter> errorDispatchProbe() {
            Filter probe = (request, response, chain) -> {
                ((HttpServletResponse) response).addHeader("X-Test-Dispatch", request.getDispatcherType().name());
                ((HttpServletResponse) response).addHeader("X-Test-Dispatch-Request-Id",
                        MDC.get(RequestLogFilter.REQUEST_ID));
                chain.doFilter(request, response);
            };
            var registration = new FilterRegistrationBean<>(probe);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
            registration.setDispatcherTypes(DispatcherType.ERROR);
            return registration;
        }
    }
    @RestController
    public static class DomainFailureController {
        @GetMapping("/api/test-domain-error")
        public ApiResponse<String> fail(@RequestParam(defaultValue = "false") boolean retry) {
            throw retry ? new ApiException(ErrorCode.NOT_FOUND, 37) : new ApiException(ErrorCode.NOT_FOUND);
        }
    }
}
