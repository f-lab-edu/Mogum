package com.mogum.auth;

import com.mogum.support.IntegrationTest;
import com.mogum.support.TestSessions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.http.ResponseCookie;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class SecurityConfigurationCrossOriginIntegrationTest extends SecurityScenario {
    private static final String ORIGIN = "https://mogum.example";

    @ParameterizedTest(name = "허용 메서드 {0}")
    @ValueSource(strings = {"GET", "POST", "PUT", "PATCH", "DELETE"})
    @DisplayName("허용 출처의 preflight는 세션과 CSRF 없이 응답한다")
    void permitsConfiguredPreflight(String method) {
        // when
        var response = preflight(ORIGIN, method, "X-XSRF-TOKEN, Content-Type").exchange();
        // then
        response.expectStatus().isOk().expectHeader().valueEquals("Access-Control-Allow-Origin", ORIGIN)
                .expectHeader().valueEquals("Access-Control-Allow-Credentials", "true")
                .expectHeader().valueEquals("Access-Control-Allow-Headers", "X-XSRF-TOKEN, Content-Type");
    }

    @ParameterizedTest(name = "거절 출처 {0}")
    @ValueSource(strings = {"https://evil.example", "https://mogum-git-x.vercel.app", "https://other.mogum.example"})
    @DisplayName("다른 출처의 preflight는 거절한다")
    void rejectsOtherOrigins(String origin) {
        // when
        var response = preflight(origin, "POST", "X-XSRF-TOKEN, Content-Type").exchange();
        // then
        response.expectStatus().isForbidden().expectHeader().doesNotExist("Access-Control-Allow-Origin");
    }

    @ParameterizedTest(name = "거절 preflight {0}")
    @ValueSource(strings = {"method", "header"})
    @DisplayName("허용하지 않은 메서드와 헤더의 preflight는 거절한다")
    void rejectsUnconfiguredMethodOrHeader(String condition) {
        // when
        var response = preflight(ORIGIN, condition.equals("method") ? "TRACE" : "POST",
                condition.equals("header") ? "X-Other" : "X-XSRF-TOKEN, Content-Type").exchange();
        // then
        response.expectStatus().isForbidden().expectHeader().doesNotExist("Access-Control-Allow-Origin");
    }

    @Test
    @DisplayName("화면의 login 경로에 기본 로그인 페이지를 만들지 않는다")
    void rejectsDefaultLoginPage() {
        // when
        var response = requests.anonymous().get().uri("/login").exchange().returnResult(String.class);
        // then
        assertError(response, 401, "UNAUTHORIZED");
    }

    @Test
    @DisplayName("CORS는 API 경로에만 적용한다")
    void limitsCorsToApiPaths() {
        // when
        var response = requests.anonymous().options().uri("/auth/google").header("Origin", ORIGIN)
                .header("Access-Control-Request-Method", "GET").exchange();
        // then
        response.expectHeader().doesNotExist("Access-Control-Allow-Origin");
    }

    @Test
    @DisplayName("허용 출처의 세션 GET은 CORS 헤더와 등록 도메인의 CSRF 쿠키를 준다")
    void issuesSharedDomainCsrfCookie() {
        // given
        var cookie = session();
        // when
        var response = client(cookie).get().uri("/api/me").header("Origin", ORIGIN).exchange().returnResult(String.class);
        // then
        assertThat(response.getStatus().value()).isEqualTo(200);
        assertThat(response.getResponseHeaders().getFirst("Access-Control-Allow-Origin")).isEqualTo(ORIGIN);
        assertThat(response.getResponseHeaders().getFirst("Access-Control-Allow-Credentials")).isEqualTo("true");
        assertCookie(response.getResponseCookies().getFirst("XSRF-TOKEN"), false, "mogum.example");
    }

    @Test
    @DisplayName("허용 출처의 로그아웃은 현재 세션과 쿠키를 보안 속성을 유지해 만료시킨다")
    void expiresCookiesAfterCrossOriginLogout() {
        // given
        var cookie = session();
        var token = client(cookie).get().uri("/api/me").header("Origin", ORIGIN).exchange()
                .returnResult().getResponseCookies().getFirst("XSRF-TOKEN");
        // when
        var response = client(cookie).post().uri("/api/auth/logout").header("Origin", ORIGIN)
                .cookie("XSRF-TOKEN", token.getValue()).header("X-XSRF-TOKEN", token.getValue())
                .exchange().returnResult(String.class);
        // then
        assertThat(response.getStatus().value()).isEqualTo(204);
        assertThat(response.getResponseHeaders().getFirst("Access-Control-Allow-Origin")).isEqualTo(ORIGIN);
        assertThat(response.getResponseHeaders().getFirst("Access-Control-Allow-Credentials")).isEqualTo("true");
        assertThat(sessions.findById(new TestSessions(sessions).id(cookie))).isNull();
        assertCookie(response.getResponseCookies().getFirst("SESSION"), true, null);
        assertThat(response.getResponseCookies().getFirst("SESSION").getMaxAge()).isZero();
        assertCookie(response.getResponseCookies().getFirst("XSRF-TOKEN"), false, "mogum.example");
        assertThat(response.getResponseCookies().getFirst("XSRF-TOKEN").getMaxAge()).isZero();
    }

    @Test
    @DisplayName("허용 출처라도 CSRF 헤더가 없으면 CORS 헤더가 있는 JSON 403이다")
    void rejectsCrossOriginLogoutWithoutCsrfHeader() {
        // given
        var cookie = session();
        var token = client(cookie).get().uri("/api/me").exchange().returnResult().getResponseCookies().getFirst("XSRF-TOKEN");
        // when
        var response = client(cookie).post().uri("/api/auth/logout").header("Origin", ORIGIN)
                .cookie("XSRF-TOKEN", token.getValue()).exchange().returnResult(String.class);
        // then
        assertError(response, 403, "CSRF_INVALID");
        assertThat(response.getResponseHeaders().getFirst("Access-Control-Allow-Origin")).isEqualTo(ORIGIN);
        assertThat(response.getResponseHeaders().getFirst("Access-Control-Allow-Credentials")).isEqualTo("true");
        assertThat(sessions.findById(new TestSessions(sessions).id(cookie))).isNotNull();
    }

    @Test
    @DisplayName("다른 출처의 실제 세션 요청도 거절한다")
    void rejectsActualRequestFromOtherOrigin() {
        // given
        var cookie = session();
        // when
        var response = client(cookie).get().uri("/api/me").header("Origin", "https://evil.example").exchange();
        // then
        response.expectStatus().isForbidden().expectHeader().doesNotExist("Access-Control-Allow-Origin");
    }

    private RestTestClient.RequestBodySpec preflight(String origin, String method, String headers) {
        return requests.anonymous().method(org.springframework.http.HttpMethod.OPTIONS).uri("/api/me").header("Origin", origin)
                .header("Access-Control-Request-Method", method).header("Access-Control-Request-Headers", headers);
    }

    private static void assertCookie(ResponseCookie cookie, boolean httpOnly, String domain) {
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isEqualTo(httpOnly);
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Lax");
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getDomain()).isEqualTo(domain);
    }
}
