package com.mogum.auth;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.domain.UserStatus;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestSessions;
import jakarta.servlet.http.Cookie;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonAssert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class SecurityConfigurationApprovalIntegrationTest extends SecurityScenario {
    @ParameterizedTest(name = "상태 {0}")
    @ValueSource(strings = {"NOT_APPLIED", "APPLIED", "REJECTED", "APPROVED"})
    @DisplayName("모든 상태에서 내 정보 신청 로그아웃을 사용할 수 있다")
    void permitsOnlyAccountActionsForEveryState(String status) {
        // given
        var client = csrfClient(accountSession(status));
        client.get().uri("/api/me").exchange().expectStatus().isOk().expectBody().jsonPath("$.data.status").isEqualTo(status);
        var apply = client.post().uri("/api/account/apply").exchange().returnResult(String.class);
        assertThat(apply.getStatus().value()).isEqualTo(200);
        assertThat(apply.getResponseBody()).contains("\"status\":\"" + (status.equals("NOT_APPLIED") ? "APPLIED" : status) + "\"");
        var next = apply.getResponseCookies().getFirst("SESSION");
        if (next != null) client = client.mutate().defaultCookie("SESSION", next.getValue()).build();

        // when
        var response = client.post().uri("/api/auth/logout").exchange();

        // then
        response.expectStatus().isNoContent();
    }

    @ParameterizedTest(name = "상태 {0} 메서드 {1} 경로 {2}")
    @CsvSource({
            "NOT_APPLIED,GET,/api/test-domain-error", "APPLIED,GET,/api/test-domain-error", "REJECTED,GET,/api/test-domain-error", "APPROVED,GET,/api/test-domain-error",
            "NOT_APPLIED,POST,/api/test-request-validation", "APPLIED,POST,/api/test-request-validation", "REJECTED,POST,/api/test-request-validation", "APPROVED,POST,/api/test-request-validation",
            "NOT_APPLIED,POST,/api/me", "APPLIED,POST,/api/me", "REJECTED,POST,/api/me", "APPROVED,POST,/api/me",
            "NOT_APPLIED,GET,/api/account/apply", "APPLIED,GET,/api/account/apply", "REJECTED,GET,/api/account/apply", "APPROVED,GET,/api/account/apply",
            "NOT_APPLIED,GET,/api/future", "APPLIED,GET,/api/future", "REJECTED,GET,/api/future", "APPROVED,GET,/api/future"})
    @DisplayName("나머지 API와 허용 경로의 다른 메서드는 승인된 회원만 통과한다")
    void deniesOtherApiByDefault(String status, String method, String path) {
        // given
        var client = csrfClient(accountSession(status));
        var request = client.method(HttpMethod.valueOf(method)).uri(path);
        if (path.equals("/api/test-request-validation")) request.contentType(MediaType.APPLICATION_JSON).body("{\"topic\":\"학습\",\"purpose\":\"공부\"}");

        // when
        var response = request.exchange().returnResult(String.class);

        // then
        if (!status.equals("APPROVED")) {
            assertThat(response.getStatus().value()).isEqualTo(403);
            JsonAssert.comparator(JsonCompareMode.STRICT).assertIsMatch(
                    "{\"code\":\"ACCOUNT_NOT_APPROVED\",\"message\":\"승인된 계정이 필요합니다\"}", response.getResponseBody());
        } else if (path.equals("/api/test-request-validation")) {
            assertThat(response.getStatus().value()).isEqualTo(200);
        } else {
            assertError(response, path.equals("/api/test-domain-error") || path.equals("/api/future") ? 404 : 405,
                    path.equals("/api/test-domain-error") || path.equals("/api/future") ? "NOT_FOUND" : "METHOD_NOT_ALLOWED");
        }
    }

    @ParameterizedTest(name = "상태 {0}")
    @ValueSource(strings = {"NOT_APPLIED", "APPLIED", "REJECTED", "APPROVED"})
    @DisplayName("승인 검사보다 CSRF 검사가 먼저여서 토큰 없는 변경은 CSRF 오류다")
    void checksCsrfBeforeApproval(String status) {
        // given
        var cookie = accountSession(status);

        // when
        var response = client(cookie).post().uri("/api/test-request-validation").exchange().returnResult(String.class);

        // then
        assertError(response, 403, "CSRF_INVALID");
    }

    @ParameterizedTest(name = "비인증 메서드 {0}")
    @ValueSource(strings = {"GET", "POST"})
    @DisplayName("비로그인은 승인 검사와 CSRF보다 먼저 기존 인증 오류로 응답한다")
    void keepsUnauthorizedPrecedence(String method) {
        // when
        var response = requests.anonymous().method(HttpMethod.valueOf(method))
                .uri("/api/account/apply").exchange().returnResult(String.class);
        // then
        assertError(response, 401, "UNAUTHORIZED");
    }

    @ParameterizedTest(name = "승인 전 상태 {0}")
    @ValueSource(strings = {"APPLIED", "REJECTED"})
    @DisplayName("같은 세션도 운영자가 승인하면 다음 API 요청부터 권한이 바뀐다")
    void reloadsApprovalForExistingSession(String status) {
        // given
        var cookie = accountSession(status);
        var client = client(cookie);
        client.get().uri("/api/test-domain-error").exchange().expectStatus().isForbidden();
        jdbc.update("UPDATE users SET status = 'APPROVED', approved_at = now(), credit_refill_at = now() + interval '30 days', updated_at = now()");

        // when
        var response = client.get().uri("/api/test-domain-error").exchange().returnResult(String.class);

        // then
        assertError(response, 404, "NOT_FOUND");
    }

    private Cookie accountSession(String status) {
        var helper = new TestSessions(sessions);
        if (status.equals("NOT_APPLIED")) return helper.createNotApplied(new AccountPrincipal.NotApplied("google", "new", "new@example.com", "새 이름"));
        var user = users.save(UserFixtures.withStatus(UserFixtures.user("approval@example.com"), UserStatus.valueOf(status)));
        return helper.create(user.getId());
    }

    private RestTestClient csrfClient(Cookie cookie) {
        var client = client(cookie);
        var token = client.get().uri("/api/me").exchange().returnResult().getResponseCookies().getFirst("XSRF-TOKEN");
        return client.mutate().defaultCookie("XSRF-TOKEN", token.getValue()).defaultHeader("X-XSRF-TOKEN", token.getValue()).build();
    }
}
