package com.mogum.support;

import com.mogum.common.api.ApiResponse;
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.common.logging.RequestLogFilter;
import com.mogum.job.JobIntakeHandler;
import com.mogum.job.JobRequest;
import com.mogum.job.domain.JobType;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
        IntegrationTestConfiguration.DomainFailureController.class,
        IntegrationTestConfiguration.JobIntakeHandlers.class,
        IntegrationTestConfiguration.JobGenerators.class})
public class IntegrationTestConfiguration {
    /** 기본 통합 시험은 여섯 종류를 허용하며, 처리기 없는 운영 구성 시험만 이 묶음을 뺀다. */
    @TestConfiguration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "test.job-intake-handlers", havingValue = "true", matchIfMissing = true)
    public static class JobIntakeHandlers {
        @Bean
        public JobIntakeHandler newCourseIntake() { return new TestJobIntakeHandler(JobType.NEW_COURSE); }
        @Bean
        public JobIntakeHandler newRoadmapIntake() { return new TestJobIntakeHandler(JobType.NEW_ROADMAP); }
        @Bean
        public JobIntakeHandler planRoadmapCourseIntake() { return new TestJobIntakeHandler(JobType.PLAN_ROADMAP_COURSE); }
        @Bean
        public JobIntakeHandler replanCourseIntake() { return new TestJobIntakeHandler(JobType.REPLAN_COURSE); }
        @Bean
        public JobIntakeHandler replanRoadmapIntake() { return new TestJobIntakeHandler(JobType.REPLAN_ROADMAP); }
        @Bean
        public JobIntakeHandler materialIntake() { return new TestJobIntakeHandler(JobType.MATERIAL); }
    }

    /** 만드는 작업 시험만 쓰는 확인 처리기. 원문을 로그나 예외에 넣지 않는다. */
    public static final class TestJobIntakeHandler implements JobIntakeHandler {
        public static final String REJECT_TOPIC = "test-intake-rejected";
        private final JobType type;
        public TestJobIntakeHandler(JobType type) { this.type = type; }
        @Override
        public JobType type() { return type; }
        @Override
        public void check(long userId, JobRequest request) {
            if (request.input() != null && REJECT_TOPIC.equals(request.input().getTopic())) {
                throw new ApiException(ErrorCode.INVALID_REQUEST);
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class JobGenerators {
        @Bean public TestJobGenerator newCourseGenerator(jakarta.persistence.EntityManager em, TestClock clock) {
            return new TestJobGenerator(JobType.NEW_COURSE, em, clock);
        }
        @Bean public TestJobGenerator newRoadmapGenerator(jakarta.persistence.EntityManager em, TestClock clock) {
            return new TestJobGenerator(JobType.NEW_ROADMAP, em, clock);
        }
        @Bean public TestJobGenerator planRoadmapCourseGenerator(jakarta.persistence.EntityManager em, TestClock clock) {
            return new TestJobGenerator(JobType.PLAN_ROADMAP_COURSE, em, clock);
        }
        @Bean public TestJobGenerator replanCourseGenerator(jakarta.persistence.EntityManager em, TestClock clock) {
            return new TestJobGenerator(JobType.REPLAN_COURSE, em, clock);
        }
        @Bean public TestJobGenerator replanRoadmapGenerator(jakarta.persistence.EntityManager em, TestClock clock) {
            return new TestJobGenerator(JobType.REPLAN_ROADMAP, em, clock);
        }
        @Bean public TestJobGenerator materialGenerator(jakarta.persistence.EntityManager em, TestClock clock) {
            return new TestJobGenerator(JobType.MATERIAL, em, clock);
        }
    }

    /** 작업 ID별 계획. 첫 호출만 붙잡으므로 되찾은 새 호출은 옛 호출과 따로 끝낼 수 있다. */
    public static final class TestJobGenerator implements com.mogum.generation.JobGenerator {
        private final JobType type;
        private final jakarta.persistence.EntityManager em;
        private final TestClock clock;
        private final java.util.concurrent.ConcurrentMap<Long, Plan> plans = new java.util.concurrent.ConcurrentHashMap<>();

        public TestJobGenerator(JobType type, jakarta.persistence.EntityManager em, TestClock clock) {
            this.type = type;
            this.em = em;
            this.clock = clock;
        }
        @Override public JobType type() { return type; }
        public Plan plan(long id) { return plans.computeIfAbsent(id, ignored -> new Plan()); }
        public void reset() { plans.clear(); }

        @Override
        public com.mogum.generation.GenerationOutcome generate(com.mogum.generation.JobSnapshot job) {
            var plan = plan(job.id());
            int call = plan.calls.incrementAndGet();
            plan.snapshots.add(job);
            plan.outsideTransaction &= !org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                    && !org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(em.getEntityManagerFactory());
            plan.mdc = "jobId=" + MDC.get(RequestLogFilter.JOB_ID) + ",userId=" + MDC.get(RequestLogFilter.USER_ID);
            if (call == 1 && plan.holding) {
                plan.entered.countDown();
                try {
                    if (!plan.released.await(15, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Stub not released");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Stub interrupted");
                }
            }
            return call <= plan.failures ? plan.failure
                    : new com.mogum.generation.GenerationOutcome.Success(new TestResult("LEAKCHECK-result"));
        }

        @Override
        public com.mogum.generation.ResultRef save(com.mogum.generation.JobSnapshot job, com.mogum.generation.GeneratedResult result) {
            if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException("Stub save requires transaction");
            }
            var plan = plan(job.id());
            plan.saves.incrementAndGet();
            String text = ((TestResult) result).text();
            long id = switch (type) {
                case NEW_COURSE -> {
                    var course = com.mogum.course.domain.Course.single(job.userId(), input(job.input()), text);
                    em.persist(course);
                    yield course.getId();
                }
                case NEW_ROADMAP -> {
                    var roadmap = com.mogum.course.domain.Roadmap.plan(job.userId(), input(job.input()), text, text);
                    em.persist(roadmap);
                    yield roadmap.getId();
                }
                case MATERIAL -> {
                    var lesson = em.find(com.mogum.course.domain.Lesson.class, job.targetId());
                    em.persist(com.mogum.course.domain.Material.write(lesson, text));
                    yield lesson.getId();
                }
                case PLAN_ROADMAP_COURSE -> {
                    var course = em.find(com.mogum.course.domain.Course.class, job.targetId());
                    em.persist(com.mogum.course.domain.Lesson.listed(course, 1, text, text, java.util.List.of()));
                    em.flush();
                    em.createNativeQuery("UPDATE courses SET status = 'REVIEWING', updated_at = :now WHERE id = :id")
                            .setParameter("now", clock.instant()).setParameter("id", course.getId()).executeUpdate();
                    yield course.getId();
                }
                case REPLAN_COURSE, REPLAN_ROADMAP -> {
                    String table = type == JobType.REPLAN_COURSE ? "courses" : "roadmaps";
                    em.createNativeQuery("UPDATE " + table + " SET title = :title, updated_at = :now WHERE id = :id")
                            .setParameter("title", text).setParameter("now", clock.instant()).setParameter("id", job.targetId()).executeUpdate();
                    yield job.targetId();
                }
            };
            if (plan.saveFailure != null) {
                var exception = new IllegalStateException(plan.saveFailure, new RuntimeException(plan.saveFailure));
                exception.addSuppressed(new IllegalArgumentException(plan.saveFailure));
                throw exception;
            }
            return new com.mogum.generation.ResultRef(id);
        }

        private static com.mogum.course.domain.LearningInput input(com.mogum.generation.JobSnapshot.Input input) {
            return new com.mogum.course.domain.LearningInput(input.topic(), input.level(), input.levelOther(),
                    input.purpose(), input.purposeOther(), input.memo());
        }
        public record TestResult(String text) implements com.mogum.generation.GeneratedResult { }

        public static final class Plan {
            private final AtomicInteger calls = new AtomicInteger();
            private final AtomicInteger saves = new AtomicInteger();
            private final java.util.List<com.mogum.generation.JobSnapshot> snapshots = new java.util.concurrent.CopyOnWriteArrayList<>();
            private final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
            private final java.util.concurrent.CountDownLatch released = new java.util.concurrent.CountDownLatch(1);
            private volatile com.mogum.generation.GenerationOutcome.Failure failure;
            private volatile int failures;
            private volatile boolean holding;
            private volatile String saveFailure;
            private volatile boolean outsideTransaction = true;
            private volatile String mdc;
            public Plan fail(com.mogum.generation.GenerationOutcome.Failure failure, int count) {
                this.failure = failure;
                this.failures = count;
                return this;
            }
            public Plan holdFirst() { holding = true; return this; }
            public Plan failSave(String message) { saveFailure = message; return this; }
            public void awaitEntered() throws InterruptedException {
                if (!entered.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Stub not entered");
            }
            public void release() { released.countDown(); }
            public int calls() { return calls.get(); }
            public int saves() { return saves.get(); }
            public java.util.List<com.mogum.generation.JobSnapshot> snapshots() { return java.util.List.copyOf(snapshots); }
            public boolean outsideTransaction() { return outsideTransaction; }
            public String mdc() { return mdc; }
        }
    }

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
        public static volatile String name;
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
                    var identity = new java.util.LinkedHashMap<String, Object>();
                    identity.put("sub", sub);
                    identity.put("email", email);
                    identity.put("email_verified", verified);
                    if (name != null) identity.put("name", name);
                    String response = userInfoFailure ? "{\"error\":\"invalid_token\",\"error_description\":\"" + sub + "\"}"
                            : new tools.jackson.databind.ObjectMapper().writeValueAsString(identity);
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
