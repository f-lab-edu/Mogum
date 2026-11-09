package com.mogum.common.api;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestRequests;
import com.mogum.support.TestSessions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.json.JsonAssert;
import org.springframework.test.json.JsonCompareMode;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class RequestValidationIntegrationTest {
    @Autowired TestRequests requests;
    @Autowired UserRepository users;
    @Autowired JdbcIndexedSessionRepository sessions;
    RestTestClient authenticated;
    RestTestClient sessionClient;

    @BeforeEach
    void prepareUserRequest() {
        var user = users.save(UserFixtures.approved("validation@example.com"));
        authenticated = requests.asUser(user.getId());
        var cookie = new TestSessions(sessions).create(user.getId());
        sessionClient = requests.anonymous().mutate().defaultCookie("SESSION", cookie.getValue()).build();
    }

    @Test
    @DisplayName("정상 요청은 data 안에 응답한다")
    void wrapsSuccessfulRequestInData() throws Exception {
        // when
        var response = post("""
                {"topic":"학습","purpose":"개념 이해"}
                """);
        // then
        assertThat(response.getStatus().value()).isEqualTo(200);
        JsonAssert.comparator(JsonCompareMode.STRICT).assertIsMatch("""
                {"data":{"topic":"학습","purpose":"개념 이해"}}
                """, response.getResponseBody());
    }

    @Test
    @DisplayName("한 칸 초과는 세 키와 해당 필드만 싣는다")
    void returnsOnlyViolatingField() throws Exception {
        // when
        var response = post("""
                {"topic":"private-topic","purpose":"학습"}
                """);
        // then
        assertError(response, "FIELD_INVALID", ",\"fields\":[{\"field\":\"topic\",\"reason\":\"TOO_LONG\"}]", "private-topic");
    }

    @ParameterizedTest(name = "정렬 반복 {0}")
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2, 3})
    @DisplayName("두 칸 초과는 항상 이름순이다")
    void sortsMultipleViolations(int repetition) throws Exception {
        // when
        var response = post("""
                    {"topic":"private-topic","purpose":"private-purpose"}
                    """);
        // then
        assertError(response, "FIELD_INVALID", ",\"fields\":[{\"field\":\"purpose\",\"reason\":\"TOO_LONG\"},{\"field\":\"topic\",\"reason\":\"TOO_LONG\"}]",
                    "private-topic", "private-purpose");
    }

    @ParameterizedTest(name = "허용 경계 {0}")
    @ValueSource(strings = {"가나다라마", "😀😀가"})
    @DisplayName("한글과 이모지는 UTF16 다섯 단위까지 허용한다")
    void acceptsUtf16Boundary(String topic) {
        // when
        var response = post("{\"topic\":\"" + topic + "\",\"purpose\":\"학습\"}");
        // then
        assertThat(response.getStatus().value()).isEqualTo(200);
    }

    @ParameterizedTest(name = "초과 경계 {0}")
    @ValueSource(strings = {"가나다라마바", "😀😀😀"})
    @DisplayName("한글과 이모지의 UTF16 길이가 상한을 넘으면 거절한다")
    void rejectsUtf16Overflow(String topic) {
        // when
        var response = post("{\"topic\":\"" + topic + "\",\"purpose\":\"학습\"}");
        // then
        assertError(response, "FIELD_INVALID", ",\"fields\":[{\"field\":\"topic\",\"reason\":\"TOO_LONG\"}]", topic, "학습");
    }

    @ParameterizedTest(name = "필수값 요청 {0}")
    @ValueSource(strings = {"{\"topic\":\"\",\"purpose\":\"private\"}",
            "{\"topic\":\"   \",\"purpose\":\"private\"}", "{\"purpose\":\"private\"}"})
    @DisplayName("빈 문자열 공백 누락은 REQUIRED다")
    void rejectsMissingRequiredValue(String body) {
        // when
        var response = post(body);
        // then
        assertError(response, "FIELD_INVALID", ",\"fields\":[{\"field\":\"topic\",\"reason\":\"REQUIRED\"}]", "private");
    }

    @Test
    @DisplayName("초과와 다른 칸 누락을 함께 싣는다")
    void returnsOverflowAndMissingField() throws Exception {
        // when
        var response = post("""
                {"topic":"private-topic"}
                """);
        // then
        assertError(response, "FIELD_INVALID",
                ",\"fields\":[{\"field\":\"purpose\",\"reason\":\"REQUIRED\"},{\"field\":\"topic\",\"reason\":\"TOO_LONG\"}]",
                "private-topic");
    }

    @ParameterizedTest(name = "reason 정렬 반복 {0}")
    @ValueSource(ints = {1, 2, 3})
    @DisplayName("상한을 넘는 공백은 같은 칸의 두 reason을 이름순으로 싣는다")
    void sortsReasonsForOneField(int repetition) throws Exception {
        // when
        var response = post("""
                    {"topic":"      ","purpose":"private"}
                    """);
        // then
        assertError(response, "FIELD_INVALID",
                    ",\"fields\":[{\"field\":\"topic\",\"reason\":\"REQUIRED\"},{\"field\":\"topic\",\"reason\":\"TOO_LONG\"}]",
                    "      ", "private");
    }

    @Test
    @DisplayName("NotNull 위반도 REQUIRED다")
    void mapsNotNullToRequired() throws Exception {
        // when
        var response = post("/api/test-request-validation-other", """
                {"topic":"학습","purpose":"학습"}
                """);
        // then
        assertError(response, "FIELD_INVALID", ",\"fields\":[{\"field\":\"requestId\",\"reason\":\"REQUIRED\"}]", "학습");
    }

    @Test
    @DisplayName("Size 하한 위반은 fields 없는 INVALID REQUEST다")
    void rejectsSizeUnderflowWithoutFields() throws Exception {
        // when
        var response = post("/api/test-request-validation-other", """
                {"topic":"가","purpose":"학습","requestId":"id"}
                """);
        // then
        assertError(response, "INVALID_REQUEST", "", "가", "학습", "id");
    }

    @Test
    @DisplayName("대응하는 reason이 없는 Pattern 위반이 섞이면 fields를 생략한다")
    void omitsFieldsForUnsupportedConstraint() throws Exception {
        // when
        var response = post("/api/test-request-validation-other", """
                {"topic":"private-topic","purpose":"private-purpose","requestId":"id"}
                """);
        // then
        assertError(response, "INVALID_REQUEST", "", "private-topic", "private-purpose", "id");
    }

    @Test
    @DisplayName("잘못된 JSON은 원문 없는 INVALID REQUEST다")
    void rejectsMalformedJsonWithoutInput() throws Exception {
        // when
        var response = post("{\"topic\":\"private-topic\",");
        // then
        assertError(response, "INVALID_REQUEST", "", "private-topic");
    }

    @Test
    @DisplayName("타입 불일치는 원문 없는 INVALID REQUEST다")
    void rejectsTypeMismatchWithoutInput() throws Exception {
        // when
        var response = post("""
                {"topic":["private-topic"],"purpose":"private"}
                """);
        // then
        assertError(response, "INVALID_REQUEST", "", "private-topic", "private");
    }

    @ParameterizedTest(name = "입력 조건 {0}")
    @ValueSource(strings = {"json", "validation", "type", "401", "403", "404", "405"})
    @DisplayName("입력 원문과 4xx의 WARN ERROR를 로그에 남기지 않는다")
    void keepsInputAndWarningsOutOfClientErrorLogs(String condition, CapturedOutput output) throws Exception {
        // given
        String marker = "LEAKCHECK-validation-marker";
        int offset = output.getAll().length();
        var request = switch (condition) {
            case "json" -> authenticated.post().uri("/api/test-request-validation").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body("{\"topic\":\"" + marker + "\",");
            case "validation" -> authenticated.post().uri("/api/test-request-validation").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body("{\"topic\":\"" + marker + "\",\"purpose\":\"학습\"}");
            case "type" -> authenticated.post().uri("/api/test-request-validation").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body("{\"topic\":[\"" + marker + "\"],\"purpose\":\"학습\"}");
            default -> {
                var selected = condition.equals("401") ? requests.anonymous()
                        : condition.equals("403") ? sessionClient : authenticated;
                String path = condition.equals("403") ? "/api/auth/logout"
                        : condition.equals("404") ? "/api/test-missing" : "/api/me";
                yield selected.method(condition.equals("403") || condition.equals("405")
                        ? org.springframework.http.HttpMethod.POST : org.springframework.http.HttpMethod.GET)
                        .uri(path + "?private=" + marker);
            }
        };
        int expectedStatus = switch (condition) { case "json", "validation", "type" -> 400; default -> Integer.parseInt(condition); };
        // when
        var response = request.exchange().returnResult(String.class);
        // then
        assertThat(response.getStatus().value()).isEqualTo(expectedStatus);
        String id = java.util.Optional.ofNullable(response.getResponseHeaders().getFirst("X-Request-Id")).orElseThrow();
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (output.getOut().lines().noneMatch(line -> line.contains("requestId=" + id)
                && line.contains("access method=")) && System.nanoTime() < deadline) Thread.sleep(10);
        var requestLines = output.getOut().lines().filter(line -> line.contains("requestId=" + id)).toList();
        assertThat(requestLines).hasSize(1);
        assertThat(requestLines.getFirst().contains(" INFO ") && requestLines.getFirst().contains("access method=")).isTrue();
        assertThat(requestLines.getFirst().contains("status=" + expectedStatus)).isTrue();
        assertThat(output.getAll().contains(marker)).isFalse();
        assertThat(response.getResponseBody().contains(marker)).isFalse();
        assertThat(output.getAll().substring(offset).lines().noneMatch(line -> line.contains(" WARN ") || line.contains(" ERROR "))).isTrue();
    }

    private EntityExchangeResult<String> post(String body) {
        return post("/api/test-request-validation", body);
    }

    private EntityExchangeResult<String> post(String path, String body) {
        return authenticated.post().uri(path).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(body).exchange().returnResult(String.class);
    }

    private static void assertError(EntityExchangeResult<String> response, String code, String fields, String... inputs) {
        assertThat(response.getStatus().value()).isEqualTo(400);
        assertThat(java.util.Optional.ofNullable(response.getResponseHeaders().getFirst("Content-Type")).orElseThrow().startsWith("application/json")).isTrue();
        assertThat(java.util.Optional.ofNullable(response.getResponseHeaders().getFirst("Retry-After")).isEmpty()).isTrue();
        var message = code.equals("FIELD_INVALID") ? "입력을 확인해 주십시오" : "요청을 확인할 수 없습니다";
        JsonAssert.comparator(JsonCompareMode.STRICT).assertIsMatch(
                "{\"code\":\"" + code + "\",\"message\":\"" + message + "\"" + fields + "}", response.getResponseBody());
        for (var input : inputs) assertThat(response.getResponseBody().contains(input)).isFalse();
    }

}
