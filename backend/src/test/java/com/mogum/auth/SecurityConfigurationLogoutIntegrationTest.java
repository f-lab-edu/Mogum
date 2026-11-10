package com.mogum.auth;

import com.mogum.support.IntegrationTest;
import com.mogum.support.TestSessions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class SecurityConfigurationLogoutIntegrationTest extends SecurityScenario {
    @ParameterizedTest(name = "세션 종류 {0}")
    @ValueSource(strings = {"member", "not-applied"})
    @DisplayName("로그아웃은 현재 세션만 삭제하고 보안 속성을 유지한 만료 쿠키를 준다")
    void logsOutOnlyCurrentDevice(String kind) {
        // given
        var helper = new TestSessions(sessions);
        var identity = new AccountPrincipal.NotApplied("google", "sub", "new@example.com", "새 이름");
        var cookie = kind.equals("member") ? session() : helper.createNotApplied(identity);
        var second = kind.equals("member")
                ? helper.create(users.findByProviderAndSubject("google", "session-sub").orElseThrow().getId())
                : helper.createNotApplied(identity);
        var me = client(cookie).get().uri("/api/me").exchange().returnResult(String.class);
        var token = me.getResponseCookies().getFirst("XSRF-TOKEN");
        assertCsrfCookie(token);
        // when
        var response = client(cookie).post().uri("/api/auth/logout").cookie("XSRF-TOKEN", token.getValue())
                .header("X-XSRF-TOKEN", token.getValue()).exchange().returnResult(String.class);
        // then
        assertThat(response.getStatus().value()).isEqualTo(204);
        assertThat(response.getResponseBody()).isNullOrEmpty();
        assertThat(response.getResponseHeaders().getFirst("Location")).isNull();
        assertThat(sessions.findById(new TestSessions(sessions).id(cookie))).isNull();
        var expired = response.getResponseCookies().getFirst("SESSION");
        assertThat(expired).isNotNull();
        assertThat(expired.getMaxAge()).isZero();
        assertThat(expired.isHttpOnly()).isTrue();
        assertThat(expired.isSecure()).isTrue();
        assertThat(expired.getSameSite()).isEqualTo("Lax");
        assertThat(expired.getPath()).isEqualTo("/");
        assertThat(expired.getDomain()).isNull();
        assertError(client(cookie).get().uri("/api/me").exchange().returnResult(String.class), 401, "UNAUTHORIZED");
        client(second).get().uri("/api/me").exchange().expectStatus().isOk();
    }

    @ParameterizedTest(name = "CSRF 헤더 {0}")
    @ValueSource(strings = {"missing", "different"})
    @DisplayName("CSRF 헤더가 없거나 다르면 JSON 403이며 세션은 유지된다")
    void rejectsInvalidCsrfWithoutDeletingSession(String header) {
        // given
        var cookie = session();
        var token = client(cookie).get().uri("/api/me").exchange().returnResult().getResponseCookies().getFirst("XSRF-TOKEN");
        var request = client(cookie).post().uri("/api/auth/logout").cookie("XSRF-TOKEN", token.getValue());
        if (header.equals("different")) request.header("X-XSRF-TOKEN", "different");
        // when
        var response = request.exchange().returnResult(String.class);
        // then
        assertError(response, 403, "CSRF_INVALID");
        assertThat(sessions.findById(new TestSessions(sessions).id(cookie))).isNotNull();
        client(cookie).get().uri("/api/me").exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("세션 없는 로그아웃도 CSRF가 없으면 JSON 403이다")
    void requiresCsrfForAnonymousLogout() {
        // when
        var response = requests.anonymous().post().uri("/api/auth/logout").exchange().returnResult(String.class);
        // then
        assertError(response, 403, "CSRF_INVALID");
    }

    @ParameterizedTest(name = "세션 쿠키 {0}")
    @ValueSource(strings = {"", "unknown"})
    @DisplayName("세션이 없거나 모르는 세션이어도 유효한 CSRF 로그아웃은 204다")
    void acceptsAnonymousLogoutWithValidCsrf(String sessionValue) {
        // given
        var token = requests.anonymous().get().uri("/api/me").exchange().returnResult().getResponseCookies().getFirst("XSRF-TOKEN");
        assertCsrfCookie(token);
        // when
        var response = requests.anonymous().post().uri("/api/auth/logout").cookie("SESSION", sessionValue)
                .cookie("XSRF-TOKEN", token.getValue()).header("X-XSRF-TOKEN", token.getValue()).exchange();
        // then
        response.expectStatus().isNoContent().expectHeader().doesNotExist("Location");
    }

    private static void assertCsrfCookie(org.springframework.http.ResponseCookie cookie) {
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isFalse();
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Lax");
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getDomain()).isEqualTo("mogum.example");
    }
}
