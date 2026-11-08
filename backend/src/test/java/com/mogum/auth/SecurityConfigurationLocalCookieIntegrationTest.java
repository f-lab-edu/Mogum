package com.mogum.auth;

import com.mogum.support.LocalCookieIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

import static org.assertj.core.api.Assertions.assertThat;

@LocalCookieIntegrationTest
class SecurityConfigurationLocalCookieIntegrationTest extends SecurityScenario {
    @Test
    @DisplayName("로컬 CSRF 쿠키도 Domain 없이 발급된다")
    void issuesHostOnlyCsrfCookie() {
        // when
        var response = requests.anonymous().get().uri("/api/me").exchange().returnResult(String.class);
        // then
        assertError(response, 401, "UNAUTHORIZED");
        assertCookie(response.getResponseCookies().getFirst("XSRF-TOKEN"));
    }

    @Test
    @DisplayName("로컬 CSRF 쿠키를 쓰는 로그아웃도 Domain 없는 만료 쿠키를 준다")
    void expiresHostOnlyCookiesOnLogout() {
        // given
        var cookie = session();
        var token = client(cookie).get().uri("/api/me").exchange().returnResult().getResponseCookies().getFirst("XSRF-TOKEN");
        // when
        var response = client(cookie).post().uri("/api/auth/logout").cookie("XSRF-TOKEN", token.getValue())
                .header("X-XSRF-TOKEN", token.getValue()).exchange().returnResult(String.class);
        // then
        assertThat(response.getStatus().value()).isEqualTo(204);
        assertCookie(response.getResponseCookies().getFirst("XSRF-TOKEN"));
        assertThat(response.getResponseCookies().getFirst("XSRF-TOKEN").getMaxAge()).isZero();
        assertThat(response.getResponseCookies().getFirst("SESSION").getDomain()).isNull();
    }

    private static void assertCookie(ResponseCookie cookie) {
        assertThat(cookie).isNotNull();
        assertThat(cookie.getDomain()).isNull();
        assertThat(cookie.isHttpOnly()).isFalse();
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Lax");
        assertThat(cookie.getPath()).isEqualTo("/");
    }
}
