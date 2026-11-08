package com.mogum.auth;

import com.mogum.support.IntegrationTest;
import com.mogum.support.TestSessions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.json.JsonCompareMode;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class SecurityConfigurationErrorIntegrationTest extends SecurityScenario {
    @ParameterizedTest(name = "오류 경로 {0} 상태 {1}")
    @CsvSource({"/api/does-not-exist,404,NOT_FOUND", "/api/test-servlet-errors/bad-request,400,INVALID_REQUEST",
            "/api/test-servlet-errors/failure,500,INTERNAL_ERROR", "/api/test-request-log-failure,500,INTERNAL_ERROR",
            "/does-not-exist,404,NOT_FOUND", "/api/test-domain-error,404,NOT_FOUND"})
    @DisplayName("서블릿과 MVC와 보안과 도메인 오류는 공통 JSON을 유지한다")
    void serializesErrorsThroughRealHttp(String path, int status, String code) {
        // given
        var cookie = approvedSession();
        // when
        var response = client(cookie).get().uri(path).exchange().returnResult(String.class);
        // then
        assertError(response, status, code);
        assertThat(sessions.findById(new TestSessions(sessions).id(cookie))).isNotNull();
    }

    @Test
    @DisplayName("인증과 CSRF를 거친 잘못된 메서드는 Allow 헤더가 있는 JSON 405다")
    void returnsMethodNotAllowedAfterAuthentication() {
        // given
        var cookie = approvedSession();
        var user = users.findByProviderAndSubject("google", "session-sub").orElseThrow();
        var client = requests.asUser(user.getId());
        // when
        var response = client.post().uri("/api/me").exchange().returnResult(String.class);
        // then
        assertError(response, 405, "METHOD_NOT_ALLOWED");
        assertThat(response.getResponseHeaders().getFirst("Allow")).contains("GET");
        client(cookie).get().uri("/api/me").exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("도메인 오류의 재시도 초는 본문과 헤더에 함께 실린다")
    void returnsRetryDelayInBodyAndHeader() {
        // given
        var cookie = approvedSession();
        // when
        var response = client(cookie).get().uri("/api/test-domain-error?retry=true").exchange();
        // then
        response.expectStatus().isNotFound().expectHeader().doesNotExist("Location")
                .expectHeader().contentTypeCompatibleWith("application/json").expectHeader().valueEquals("Retry-After", "37")
                .expectBody().json("""
                {"code":"NOT_FOUND","message":"찾을 수 없습니다","retryAfterSeconds":37}
                """, JsonCompareMode.STRICT);
    }

    @ParameterizedTest(name = "등록되지 않은 로그인 경로 인증 {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("미등록 로그인 경로는 ERROR 디스패치로 JSON 404를 주고 오류 로그가 없다")
    void dispatchesUnknownRegistrationWithoutErrorLog(boolean authenticated,
            org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        // given
        int logOffset = output.getAll().length();
        var client = authenticated ? client(approvedSession()) : requests.anonymous();
        // when
        var response = client.get().uri("/auth/foo").exchange().returnResult(String.class);
        // then
        assertError(response, 404, "NOT_FOUND");
        assertThat(response.getResponseHeaders().getOrEmpty("X-Test-Dispatch")).containsExactly("ERROR");
        var id = response.getResponseHeaders().getFirst("X-Request-Id");
        assertThat(response.getResponseHeaders().getOrEmpty("X-Test-Dispatch-Request-Id"))
                .containsExactly(id);
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(3).toNanos();
        while (output.getOut().lines().noneMatch(line -> line.contains("requestId=" + id) && line.contains("access method="))
                && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(output.getAll().substring(logOffset).lines()).noneMatch(line -> line.contains(" ERROR "));
    }
}
