package com.mogum.common;

import com.mogum.account.domain.User;
import com.mogum.account.domain.UserRole;
import com.mogum.account.repository.UserRepository;
import com.mogum.support.PostgresTestConfiguration;
import com.mogum.support.TestSessions;
import jakarta.servlet.http.Cookie;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.json.JsonAssert;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RequestValidationIT.ValidationController.class)
class RequestValidationIT extends PostgresTestConfiguration {
    @Autowired private UserRepository users;
    @Autowired private JdbcIndexedSessionRepository sessions;
    @Value("${local.server.port}") private int port;
    private TestSessions testSessions;
    private User user;
    private Cookie session;
    private String token;
    private HttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        var key = "validation-" + UUID.randomUUID();
        user = users.save(User.join("google", key, key + "@example.com", UserRole.ADMIN, Instant.now()));
        testSessions = new TestSessions(sessions);
        session = testSessions.create(user.getId());
        client = HttpClient.newHttpClient();
        var response = client.send(HttpRequest.newBuilder(uri("/api/me"))
                .header("Cookie", "SESSION=" + session.getValue()).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        token = response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("XSRF-TOKEN="))
                .map(value -> value.substring("XSRF-TOKEN=".length(), value.indexOf(';')))
                .findFirst().orElseThrow();
    }

    @AfterEach
    void cleanUp() {
        if (client != null) client.close();
        if (session != null) sessions.deleteById(testSessions.id(session));
        if (user != null) users.deleteById(user.getId());
    }

    @Test
    void 정상_요청은_data_안에_응답한다() throws Exception {
        var response = post("""
                {"topic":"학습","purpose":"개념 이해"}
                """);
        assertEquals(200, response.statusCode());
        JsonAssert.comparator(JsonCompareMode.STRICT).assertIsMatch("""
                {"data":{"topic":"학습","purpose":"개념 이해"}}
                """, response.body());
    }

    @Test
    void 한_칸_초과는_세_키와_해당_필드만_싣는다() throws Exception {
        assertError(post("""
                {"topic":"private-topic","purpose":"학습"}
                """), "STRUCTURAL_LIMIT", ",\"fields\":[\"topic\"]", "private-topic");
    }

    @Test
    void 두_칸_초과는_항상_이름순이다() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertError(post("""
                    {"topic":"private-topic","purpose":"private-purpose"}
                    """), "STRUCTURAL_LIMIT", ",\"fields\":[\"purpose\",\"topic\"]",
                    "private-topic", "private-purpose");
        }
    }

    @Test
    void 한글_다섯_자는_허용하고_여섯_자는_거부한다() throws Exception {
        assertEquals(200, post("""
                {"topic":"가나다라마","purpose":"학습"}
                """).statusCode());
        assertError(post("""
                {"topic":"가나다라마바","purpose":"학습"}
                """), "STRUCTURAL_LIMIT", ",\"fields\":[\"topic\"]", "가나다라마바", "학습");
    }

    @Test
    void 이모지도_UTF16_단위로_센다() throws Exception {
        assertEquals(200, post("""
                {"topic":"😀😀가","purpose":"학습"}
                """).statusCode());
        assertError(post("""
                {"topic":"😀😀😀","purpose":"학습"}
                """), "STRUCTURAL_LIMIT", ",\"fields\":[\"topic\"]", "😀😀😀", "학습");
    }

    @Test
    void 빈_값은_fields_없는_INVALID_REQUEST다() throws Exception {
        assertError(post("""
                {"topic":"","purpose":"private"}
                """), "INVALID_REQUEST", "", "private");
        assertError(post("""
                {"topic":"   ","purpose":"private"}
                """), "INVALID_REQUEST", "", "private");
    }

    @Test
    void 초과와_다른_칸_누락이_섞이면_fields를_생략한다() throws Exception {
        assertError(post("""
                {"topic":"private-topic"}
                """), "INVALID_REQUEST", "", "private-topic");
    }

    @Test
    void 잘못된_JSON은_원문_없는_INVALID_REQUEST다() throws Exception {
        assertError(post("{\"topic\":\"private-topic\","), "INVALID_REQUEST", "", "private-topic");
    }

    @Test
    void 타입_불일치는_원문_없는_INVALID_REQUEST다() throws Exception {
        assertError(post("""
                {"topic":["private-topic"],"purpose":"private"}
                """), "INVALID_REQUEST", "", "private-topic", "private");
    }

    private HttpResponse<String> post(String body) throws Exception {
        return client.send(HttpRequest.newBuilder(uri("/api/test-request-validation"))
                .header("Cookie", "SESSION=" + session.getValue() + "; XSRF-TOKEN=" + token)
                .header("X-XSRF-TOKEN", token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static void assertError(HttpResponse<String> response, String code, String fields, String... inputs) {
        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
        assertTrue(response.headers().firstValue("Retry-After").isEmpty());
        var message = code.equals("STRUCTURAL_LIMIT") ? "입력 길이를 확인해 주십시오" : "요청을 확인할 수 없습니다";
        JsonAssert.comparator(JsonCompareMode.STRICT).assertIsMatch(
                "{\"code\":\"" + code + "\",\"message\":\"" + message + "\"" + fields + "}", response.body());
        for (var input : inputs) assertFalse(response.body().contains(input), response.body());
    }

    @RestController
    static class ValidationController {
        @PostMapping("/api/test-request-validation")
        public ApiResponse<ValidationRequest> validate(@Valid @RequestBody ValidationRequest request) {
            return ApiResponse.of(request);
        }
    }

    record ValidationRequest(@NotBlank @Size(max = 5) String topic,
                             @NotBlank @Size(max = 10) String purpose) { }
}
