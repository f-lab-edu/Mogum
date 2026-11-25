package com.mogum.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import javax.sql.DataSource;

import org.slf4j.MDC;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.web.servlet.AbstractFilterRegistrationBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.mogum.account.service.CreditLedger;
import com.mogum.auth.SecurityConfiguration;
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ApiResponse;
import com.mogum.common.api.ErrorCode;
import com.mogum.common.logging.RequestLogFilter;
import com.mogum.config.CreditProperties;
import com.mogum.config.JobProperties;
import com.mogum.config.WebProperties;
import com.mogum.course.domain.Course;
import com.mogum.course.domain.LearningInput;
import com.mogum.course.domain.Lesson;
import com.mogum.course.domain.Material;
import com.mogum.course.domain.Roadmap;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.course.repository.RoadmapRepository;
import com.mogum.generation.ChatProvider;
import com.mogum.generation.EmbeddingProvider;
import com.mogum.generation.GeneratedResult;
import com.mogum.generation.GenerationOutcome;
import com.mogum.generation.JobGenerator;
import com.mogum.generation.JobSnapshot;
import com.mogum.generation.ResultRef;
import com.mogum.generation.call.domain.LlmCallOutcome;
import com.mogum.job.JobIntakeHandler;
import com.mogum.job.JobRequest;
import com.mogum.job.domain.JobType;
import com.mogum.job.repository.GenerationJobRepository;
import com.mogum.job.service.JobIntakeService;
import com.sun.net.httpserver.HttpServer;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
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
import tools.jackson.databind.ObjectMapper;

/** 전용 경로의 검증/실패 빈은 모든 통합 테스트가 같은 구성으로 쓴다. */
@TestConfiguration(proxyBeanMethods = false)
@Import({IntegrationTestConfiguration.ValidationController.class,
	IntegrationTestConfiguration.FailureController.class,
	IntegrationTestConfiguration.ServletErrors.class,
	IntegrationTestConfiguration.FilterFailure.class,
	IntegrationTestConfiguration.ErrorDispatchProbe.class,
	IntegrationTestConfiguration.DomainFailureController.class})
public class IntegrationTestConfiguration {
	/** 틀 시험이 직접 만드는 처리기와 트랜잭션 프록시. 공용 컨텍스트에는 등록하지 않는다. */
	public static final class FrameworkJobs {
		public final JobIntakeService jobIntakeService;
		public final List<JobGenerator> generators;

		public FrameworkJobs(ApplicationContext applicationContext) {
			var service = new JobIntakeService(applicationContext.getBean(GenerationJobRepository.class),
				applicationContext.getBean(CourseRepository.class),
				applicationContext.getBean(RoadmapRepository.class),
				applicationContext.getBean(LessonRepository.class), applicationContext.getBean(CreditLedger.class),
				applicationContext.getBean(CreditProperties.class), applicationContext.getBean(JobProperties.class),
				Arrays.stream(JobType.values()).<JobIntakeHandler>map(TestJobIntakeHandler::new).toList());

			var proxy = new ProxyFactory(service);
			var transactions = new TransactionInterceptor();
			transactions.setTransactionManager(applicationContext.getBean(PlatformTransactionManager.class));
			transactions.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
			proxy.addAdvice(transactions);
			jobIntakeService = (JobIntakeService)proxy.getProxy();

			generators = Arrays.stream(JobType.values()).<JobGenerator>map(type -> new TestJobGenerator(type,
				applicationContext.getBean(EntityManager.class), applicationContext.getBean(TestClock.class)))
				.toList();
		}
	}

	@Bean
	@Primary
	public TestChatProvider testChatProvider(EntityManager entityManager) {
		return new TestChatProvider(entityManager);
	}

	@Bean
	@Primary
	public TestEmbeddingProvider testEmbeddingProvider(EntityManager entityManager) {
		return new TestEmbeddingProvider(entityManager);
	}

	/** 정의별 벡터와 작업별 응답을 정하는 공용 임베딩 대역. */
	public static final class TestEmbeddingProvider implements EmbeddingProvider {
		private final ConcurrentMap<String, List<Float>> vectors = new ConcurrentHashMap<>();
		private final ConcurrentMap<Long, Deque<Reply>> replies = new ConcurrentHashMap<>();
		private final List<Request> requests = new CopyOnWriteArrayList<>();
		private final ConcurrentMap<Long, Boolean> outsideTransactions = new ConcurrentHashMap<>();
		private final EntityManagerFactory entityManagerFactory;

		public TestEmbeddingProvider(EntityManager entityManager) {
			this.entityManagerFactory = entityManager.getEntityManagerFactory();
		}

		public void reset() {
			vectors.clear();
			replies.clear();
			requests.clear();
			outsideTransactions.clear();
		}

		public void vector(String definition, List<Float> vector) {
			vectors.put(definition, List.copyOf(vector));
		}

		public void reply(long jobId, Reply... sequence) {
			replies.put(jobId, new ArrayDeque<>(List.of(sequence)));
		}

		public List<Request> requests(long jobId) {
			return requests.stream().filter(request -> request.jobId() == jobId).toList();
		}

		public boolean outsideTransaction(long jobId) {
			return Boolean.TRUE.equals(outsideTransactions.get(jobId));
		}

		@Override
		public Reply send(Request request) {
			requests.add(request);
			boolean outside = !TransactionSynchronizationManager.isActualTransactionActive()
				&& !TransactionSynchronizationManager.hasResource(entityManagerFactory);
			outsideTransactions.merge(request.jobId(), outside, (previous, current) -> previous && current);
			var sequence = replies.get(request.jobId());
			if (sequence != null) {
				synchronized (sequence) {
					if (!sequence.isEmpty()) {
						return sequence.removeFirst();
					}
				}
			}
			return new Reply(request.definitions().stream()
				.map(definition -> vectors.getOrDefault(definition, deterministicVector(definition))).toList(),
				LlmCallOutcome.SUCCEEDED, null, 100);
		}

		public static Reply failure(GenerationOutcome.Failure failure) {
			var outcome = switch (failure) {
				case INVALID_OUTPUT -> LlmCallOutcome.INVALID_OUTPUT;
				case PROVIDER_UNAVAILABLE -> LlmCallOutcome.PROVIDER_ERROR;
				case PROVIDER_REJECTED, INPUT_TOO_LARGE -> LlmCallOutcome.REJECTED;
			};
			return new Reply(null, outcome, failure, null);
		}

		private static List<Float> deterministicVector(String definition) {
			// 테스트 재현성만 위한 벡터다. 실제 의미 유사도를 흉내 내지 않는다.
			var random = new Random(definition.hashCode());
			return IntStream.range(0, 3072).mapToObj(index -> random.nextFloat() - 0.5f).toList();
		}
	}

	/** 운영 생성 흐름에서 교체하는 유일한 경계. 응답은 작업별 순서대로 소비한다. */
	public static final class TestChatProvider implements ChatProvider {
		private final ConcurrentMap<Long, Deque<Reply>> replies = new ConcurrentHashMap<>();
		private final List<Request> requests = new CopyOnWriteArrayList<>();
		private final ConcurrentMap<Long, Boolean> outsideTransactions = new ConcurrentHashMap<>();
		private final EntityManagerFactory entityManagerFactory;

		public TestChatProvider(EntityManager entityManager) {
			this.entityManagerFactory = entityManager.getEntityManagerFactory();
		}

		public void reset() {
			replies.clear();
			requests.clear();
			outsideTransactions.clear();
		}

		public boolean outsideTransaction(long jobId) {
			return Boolean.TRUE.equals(outsideTransactions.get(jobId));
		}

		public void reply(long jobId, Reply... sequence) {
			replies.put(jobId, new ArrayDeque<>(List.of(sequence)));
		}

		public List<Request> requests(long jobId) {
			return requests.stream().filter(request -> request.jobId() == jobId).toList();
		}

		@Override
		public Reply send(Request request) {
			requests.add(request);
			boolean outside = !TransactionSynchronizationManager.isActualTransactionActive()
				&& !TransactionSynchronizationManager.hasResource(entityManagerFactory);
			outsideTransactions.merge(request.jobId(), outside, (previous, current) -> previous && current);
			var sequence = replies.get(request.jobId());
			if (sequence != null) {
				synchronized (sequence) {
					if (!sequence.isEmpty()) {
						return sequence.removeFirst();
					}
				}
			}
			return success(defaultText(request.jobType()));
		}

		public static Reply success(String text) {
			return new Reply(text, LlmCallOutcome.SUCCEEDED, null, new Usage(100, 0, 50));
		}

		public static Reply failure(GenerationOutcome.Failure failure) {
			if (failure == GenerationOutcome.Failure.INVALID_OUTPUT) {
				return success("invalid output");
			}
			return new Reply(null, failure == GenerationOutcome.Failure.PROVIDER_UNAVAILABLE
				? LlmCallOutcome.PROVIDER_ERROR : LlmCallOutcome.REJECTED, failure, null);
		}

		public static String defaultText(JobType type) {
			// @checkstyle:off — 텍스트 블록의 공백 들여쓰기를 보존한다.
			if (type == JobType.MATERIAL) {
				return """
					# 이번 강의에서 배울 것
					변수와 함수를 배운다.
					## 개념 설명
					변수는 값을 담는 이름이고 함수는 동작의 묶음이다.
					## 정리
					값을 변수에 담고 함수를 호출한다.
					<!-- MOGUM_CARDS -->
					{"cards":[
					  {"question":"변수란?","answer":"값을 담는 이름","concepts":[{"name":"변수","definition":"값을 담는 이름"}]},
					  {"question":"함수란?","answer":"동작의 묶음","concepts":[{"name":"함수","definition":"동작의 묶음"}]},
					  {"question":"변수와 함수의 관계는?","answer":"변수의 값을 함수에 전달한다","concepts":[
					    {"name":"변수","definition":"값을 담는 이름"},{"name":"함수","definition":"동작의 묶음"}]}]}
					""";
			}

			String outlines = """
				[{"title":"기초", "description":"기초 설명",
				  "concepts":[{"name":"변수", "definition":"값을 담는 이름"}]},
				 {"title":"응용", "description":"응용 설명",
				  "concepts":[{"name":"함수", "definition":"동작의 묶음"}]}]
				""";
			// @checkstyle:on
			if (type == JobType.PLAN_ROADMAP_COURSE || type == JobType.REPLAN_COURSE) {
				return "{\"lessons\":" + outlines + "}";
			}
			return type == JobType.NEW_ROADMAP || type == JobType.REPLAN_ROADMAP
				? "{\"title\":\"자바 로드맵\",\"description\":\"자바를 익힌다\",\"courses\":" + outlines + "}"
				: "{\"title\":\"자바 코스\",\"lessons\":" + outlines + "}";
		}
	}

	/** 만드는 작업 시험만 쓰는 확인 처리기. 원문을 로그나 예외에 넣지 않는다. */
	public static final class TestJobIntakeHandler implements JobIntakeHandler {
		public static final String REJECT_TOPIC = "test-intake-rejected";

		private final JobType jobType;

		public TestJobIntakeHandler(JobType jobType) {
			this.jobType = jobType;
		}

		@Override
		public JobType type() {
			return jobType;
		}

		@Override
		public void check(long userId, JobRequest request) {
			if (request.input() != null && REJECT_TOPIC.equals(request.input().getTopic())) {
				throw new ApiException(ErrorCode.INVALID_REQUEST);
			}
		}
	}

	/** 작업 ID별 계획. 첫 호출만 붙잡으므로 되찾은 새 호출은 옛 호출과 따로 끝낼 수 있다. */
	public static final class TestJobGenerator implements JobGenerator {
		private final JobType jobType;
		private final EntityManager entityManager;
		private final TestClock testClock;
		private final ConcurrentMap<Long, Plan> plans = new ConcurrentHashMap<>();

		public TestJobGenerator(JobType jobType, EntityManager entityManager, TestClock testClock) {
			this.jobType = jobType;
			this.entityManager = entityManager;
			this.testClock = testClock;
		}

		@Override
		public JobType type() {
			return jobType;
		}

		public Plan plan(long id) {
			return plans.computeIfAbsent(id, ignored -> new Plan());
		}

		public void reset() {
			plans.clear();
		}

		@Override
		public GenerationOutcome generate(JobSnapshot job) {
			var plan = plan(job.id());
			int call = plan.calls.incrementAndGet();
			plan.snapshots.add(job);
			plan.outsideTransaction &= !TransactionSynchronizationManager.isActualTransactionActive()
				&& !TransactionSynchronizationManager.hasResource(entityManager.getEntityManagerFactory());
			plan.mdc = "jobId=" + MDC.get(RequestLogFilter.JOB_ID) + ",userId=" + MDC.get(RequestLogFilter.USER_ID);

			if (call == 1 && plan.holding) {
				plan.entered.countDown();
				try {
					if (!plan.released.await(15, TimeUnit.SECONDS)) {
						throw new IllegalStateException("Stub not released");
					}
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException("Stub interrupted");
				}
			}

			return call <= plan.failures ? plan.failure
				: new GenerationOutcome.Success(new TestResult("LEAKCHECK-result"));
		}

		@Override
		public ResultRef save(JobSnapshot job, GeneratedResult result) {
			if (!TransactionSynchronizationManager.isActualTransactionActive()) {
				throw new IllegalStateException("Stub save requires transaction");
			}

			var plan = plan(job.id());
			plan.saves.incrementAndGet();
			String text = ((TestResult)result).text();
			long id = switch (jobType) {
				case NEW_COURSE -> {
					var course = Course.single(job.userId(), input(job.input()), text, null);
					entityManager.persist(course);
					yield course.getId();
				}
				case NEW_ROADMAP -> {
					var roadmap = Roadmap.plan(job.userId(), input(job.input()), text, text);
					entityManager.persist(roadmap);
					yield roadmap.getId();
				}
				case MATERIAL -> {
					var lesson = entityManager.find(Lesson.class, job.targetId());
					entityManager.persist(Material.write(lesson, text));
					yield lesson.getId();
				}
				case PLAN_ROADMAP_COURSE -> {
					var course = entityManager.find(Course.class, job.targetId());
					entityManager.persist(Lesson.listed(course, 1, text, text, List.of()));
					entityManager.flush();
					entityManager.createNativeQuery(
						"UPDATE courses SET status = 'REVIEWING', updated_at = :now WHERE id = :id")
						.setParameter("now", testClock.instant())
						.setParameter("id", course.getId())
						.executeUpdate();
					yield course.getId();
				}
				case REPLAN_COURSE, REPLAN_ROADMAP -> {
					String table = jobType == JobType.REPLAN_COURSE ? "courses" : "roadmaps";
					entityManager.createNativeQuery(
						"UPDATE " + table + " SET title = :title, updated_at = :now WHERE id = :id")
						.setParameter("title", text)
						.setParameter("now", testClock.instant())
						.setParameter("id", job.targetId())
						.executeUpdate();
					yield job.targetId();
				}
			};

			if (plan.saveFailure != null) {
				var exception = new IllegalStateException(plan.saveFailure, new RuntimeException(plan.saveFailure));
				exception.addSuppressed(new IllegalArgumentException(plan.saveFailure));
				throw exception;
			}

			return new ResultRef(id);
		}

		private static LearningInput input(JobSnapshot.Input input) {
			return new LearningInput(input.topic(), input.level(), input.levelOther(),
				input.purpose(), input.purposeOther(), input.memo());
		}

		public record TestResult(String text) implements GeneratedResult {
		}

		public static final class Plan {
			private final AtomicInteger calls = new AtomicInteger();
			private final AtomicInteger saves = new AtomicInteger();
			private final List<JobSnapshot> snapshots = new CopyOnWriteArrayList<>();
			private final CountDownLatch entered = new CountDownLatch(1);
			private final CountDownLatch released = new CountDownLatch(1);
			private volatile GenerationOutcome.Failure failure;
			private volatile int failures;
			private volatile boolean holding;
			private volatile String saveFailure;
			private volatile boolean outsideTransaction = true;
			private volatile String mdc;

			public Plan fail(GenerationOutcome.Failure failure, int count) {
				this.failure = failure;
				this.failures = count;
				return this;
			}

			public Plan holdFirst() {
				holding = true;
				return this;
			}

			public Plan failSave(String message) {
				saveFailure = message;
				return this;
			}

			public void awaitEntered() throws InterruptedException {
				if (!entered.await(5, TimeUnit.SECONDS)) {
					throw new IllegalStateException("Stub not entered");
				}
			}

			public void release() {
				released.countDown();
			}

			public int calls() {
				return calls.get();
			}

			public int saves() {
				return saves.get();
			}

			public List<JobSnapshot> snapshots() {
				return List.copyOf(snapshots);
			}

			public boolean outsideTransaction() {
				return outsideTransaction;
			}

			public String mdc() {
				return mdc;
			}
		}
	}

	// 바깥 설정을 파싱할 때 단위 테스트 설정까지 자동 등록하지 않도록 비컴포넌트 안에 둔다.
	public static final class CookieUnitSupport {
		/** 세션 직렬화기 구성만 보는 단위 테스트용이며 공용 앱에는 가져오지 않는다. */
		@TestConfiguration(proxyBeanMethods = false)
		@EnableConfigurationProperties(WebProperties.class)
		public static class SessionCookieConfiguration {
			@Bean
			public CookieSerializer cookieSerializer() {
				var serializer = new DefaultCookieSerializer();
				new SecurityConfiguration().sessionCookieSecurity().customize(serializer);
				return serializer;
			}
		}
	}

	@Bean
	public TestRequests testRequests(RestTestClient restTestClient,
		JdbcIndexedSessionRepository jdbcIndexedSessionRepository) {
		return new TestRequests(restTestClient, jdbcIndexedSessionRepository);
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

		public DatabaseProbe(DataSource dataSource) {
			super(dataSource);
		}

		public void failConnections(boolean value) {
			failing = value;
		}

		public long connectionAttempts() {
			return attempts.get();
		}

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
			if (failing) {
				throw new SQLException("test database unavailable");
			}
		}
	}

	/** Google 루프백 서버도 컨텍스트 키를 바꾸지 않도록 공용 initializer에서 연결한다. */
	public static final class OAuthInitializer
		implements ApplicationContextInitializer<ConfigurableApplicationContext> {
		@Override
		public void initialize(ConfigurableApplicationContext context) {
			TestPropertyValues.of(
				"app.worker.count=0",
				"app.credit.refill-enabled=false",
				"spring.security.oauth2.client.provider.google.authorization-uri="
					+ FakeGoogle.ORIGIN + "/authorize",
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
					if (verifier != null) {
						verifier = URLDecoder.decode(verifier, StandardCharsets.UTF_8);
					}

					boolean bad = tokenFailure || "bad-token".equals(form.getFirst("code")) || verifier == null
						|| CODE_CHALLENGE.get() == null || !CODE_CHALLENGE.get().equals(challenge(verifier));
					String response = bad
						? "{\"error\":\"invalid_grant\",\"error_description\":\"" + form.getFirst("code") + "\"}"
						: "{\"access_token\":\"fake-access-token\",\"token_type\":\"Bearer\","
							+ "\"expires_in\":3600,\"id_token\":\"fake-id-token\"}";

					byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
					exchange.getResponseHeaders().set("Content-Type", "application/json");
					exchange.sendResponseHeaders(bad ? 400 : 200, bytes.length);
					exchange.getResponseBody().write(bytes);
					exchange.close();
				});

				server.createContext("/userinfo", exchange -> {
					var identity = new LinkedHashMap<String, Object>();
					identity.put("sub", sub);
					identity.put("email", email);
					identity.put("email_verified", verified);
					if (name != null) {
						identity.put("name", name);
					}
					String response = userInfoFailure
						? "{\"error\":\"invalid_token\",\"error_description\":\"" + sub + "\"}"
						: new ObjectMapper().writeValueAsString(identity);

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
				return Base64.getUrlEncoder()
					.withoutPadding()
					.encodeToString(
						MessageDigest.getInstance("SHA-256")
							.digest(verifier.getBytes(StandardCharsets.US_ASCII)));
			} catch (NoSuchAlgorithmException exception) {
				throw new AssertionError(exception);
			}
		}
	}

	@RestController
	public static class ValidationController {
		@PostMapping("/api/test-request-validation")
		public ApiResponse<ValidationRequest> validate(
			@Valid
			@RequestBody
			ValidationRequest request) {
			return ApiResponse.of(request);
		}

		@PostMapping("/api/test-request-validation-other")
		public ApiResponse<OtherValidationRequest> validateOther(
			@Valid
			@RequestBody
			OtherValidationRequest request) {
			return ApiResponse.of(request);
		}
	}

	record ValidationRequest(@NotBlank @Size(max = 5) String topic,
		@NotBlank @Size(max = 10) String purpose) {
	}

	record OtherValidationRequest(@Size(min = 2, max = 5) String topic,
		@Pattern(regexp = "학습") String purpose,
		@NotNull String requestId) {
	}

	@TestConfiguration(proxyBeanMethods = false)
	public static class ServletErrors {
		@Bean
		FilterRegistrationBean<Filter> servletErrors() {
			Filter filter = (request, response, chain) -> {
				var path = ((HttpServletRequest)request).getRequestURI();
				if (path.endsWith("/bad-request")) {
					((HttpServletResponse)response).sendError(400, "private servlet failure");
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

	record FailureResponse(String value) {
	}

	@TestConfiguration(proxyBeanMethods = false)
	public static class FilterFailure {
		@Bean
		FilterRegistrationBean<Filter> filterFailure(
			@Qualifier("securityFilterChainRegistration")
			AbstractFilterRegistrationBean<?> securityFilterRegistrationBean) {
			Filter failure = (request, response, chain) -> {
				if ("/api/test-request-log-filter-failure".equals(((HttpServletRequest)request).getRequestURI())) {
					throw new RuntimeException("request log filter failure marker");
				}

				chain.doFilter(request, response);
			};

			var registration = new FilterRegistrationBean<>(failure);
			registration.setOrder(securityFilterRegistrationBean.getOrder() + 1);
			registration.setDispatcherTypes(DispatcherType.REQUEST);
			return registration;
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	public static class ErrorDispatchProbe {
		@Bean
		FilterRegistrationBean<Filter> errorDispatchProbe() {
			Filter probe = (request, response, chain) -> {
				((HttpServletResponse)response).addHeader("X-Test-Dispatch", request.getDispatcherType().name());
				((HttpServletResponse)response).addHeader("X-Test-Dispatch-Request-Id",
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
		public ApiResponse<String> fail(
			@RequestParam(defaultValue = "false")
			boolean retry) {
			throw retry ? new ApiException(ErrorCode.NOT_FOUND, 37) : new ApiException(ErrorCode.NOT_FOUND);
		}
	}
}
