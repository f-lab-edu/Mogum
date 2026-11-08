package com.mogum.auth;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CookieAuthorizationRequestRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");
    private static final String STATE = "random-state-known-only-to-browser";
    private static final String VERIFIER = "aB09-._~".repeat(8);
    private final ClientRegistration registration = ClientRegistration.withRegistrationId("google")
            .clientId("test-client").clientSecret("test-secret")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).scope("email")
            .authorizationUri("https://provider.example/authorize").tokenUri("https://provider.example/token")
            .userInfoUri("https://provider.example/userinfo").userNameAttributeName("sub")
            .redirectUri("http://localhost:5173/auth/google/callback").build();

    @Test
    @DisplayName("JSON은 state와 발급시각과 PKCE verifier만 담고 세션을 만들지 않는다")
    void savesOnlyStateTimestampAndVerifier() {
        // given
        var repository = repository(NOW);
        var response = new MockHttpServletResponse();
        var request = new MockHttpServletRequest();

        // when
        repository.saveAuthorizationRequest(authorization(), request, response);

        // then
        assertThat(request.getSession(false)).isNull();
        var cookie = response.getCookie("MOGUM_OAUTH_REQUEST");
        assertThat(cookie).isNotNull();
        var json = new String(Base64.getUrlDecoder().decode(cookie.getValue()), StandardCharsets.UTF_8);
        assertThat(json).contains(STATE).doesNotContain("redirect", "secret");
        var saved = new ObjectMapper().readTree(json);
        assertThat(saved.size()).isEqualTo(3);
        assertThat(saved.get("code_verifier").asText()).isEqualTo(VERIFIER);
    }

    @Test
    @DisplayName("저장한 요청은 서버 설정과 쿠키의 verifier로 복원한다")
    void restoresSavedRequestFromServerConfiguration() {
        // given
        var repository = repository(NOW);
        var response = new MockHttpServletResponse();
        repository.saveAuthorizationRequest(authorization(), new MockHttpServletRequest(), response);
        var callback = callback(response.getCookie("MOGUM_OAUTH_REQUEST"), STATE);

        // when
        var restored = repository.loadAuthorizationRequest(callback);

        // then
        assertThat(restored).isNotNull();
        assertThat(restored.getRedirectUri()).isEqualTo(registration.getRedirectUri());
        assertThat(restored.getClientId()).isEqualTo(registration.getClientId());
        assertThat(restored.<String>getAttribute("registration_id")).isEqualTo("google");
        assertThat(restored.<String>getAttribute("code_verifier")).isEqualTo(VERIFIER);
    }

    @Test
    @DisplayName("요청 삭제는 복원한 요청을 반환하고 쿠키를 만료시키며 세션을 만들지 않는다")
    void removesRequestAndExpiresCookieWithoutSession() {
        // given
        var repository = repository(NOW);
        var response = new MockHttpServletResponse();
        repository.saveAuthorizationRequest(authorization(), new MockHttpServletRequest(), response);
        var callback = callback(response.getCookie("MOGUM_OAUTH_REQUEST"), STATE);
        var removed = new MockHttpServletResponse();
        repository.loadAuthorizationRequest(callback);

        // when
        var restored = repository.removeAuthorizationRequest(callback, removed);

        // then
        assertThat(restored).isNotNull();
        assertThat(removed.getCookie("MOGUM_OAUTH_REQUEST").getMaxAge()).isZero();
        assertThat(callback.getSession(false)).isNull();
    }

    @ParameterizedTest(name = "verifier 길이 {0}")
    @ValueSource(ints = {43, 128})
    @DisplayName("RFC7636 verifier의 최소와 최대 길이를 복원한다")
    void restoresVerifierAtLengthBoundaries(int length) {
        // given
        var repository = repository(NOW);
        var callback = callback(savedCookie("a".repeat(length)), STATE);

        // when
        var restored = repository.loadAuthorizationRequest(callback);

        // then
        assertThat(restored).isNotNull();
        assertThat(restored.<String>getAttribute("code_verifier")).isEqualTo("a".repeat(length));
    }

    @ParameterizedTest(name = "verifier 조건 {0}")
    @ValueSource(strings = {"missing", "empty", "short", "long", "space", "padding", "unicode"})
    @DisplayName("없거나 RFC7636 형식이 아닌 verifier는 복원하지 않는다")
    void rejectsMissingOrInvalidVerifier(String condition) {
        // given
        String verifier = switch (condition) {
            case "missing" -> null;
            case "empty" -> "";
            case "short" -> "a".repeat(42);
            case "long" -> "a".repeat(129);
            case "space" -> "a".repeat(43) + " ";
            case "padding" -> "a".repeat(43) + "=";
            default -> "a".repeat(43) + "가";
        };
        var repository = repository(NOW);
        var callback = callback(savedCookie(verifier), STATE);

        // when
        var restored = repository.loadAuthorizationRequest(callback);

        // then
        assertThat(restored).isNull();
    }

    @Test
    @DisplayName("길이 상한을 넘는 쿠키는 복원하지 않는다")
    void rejectsOversizedCookie() {
        // given
        var repository = repository(NOW);
        var callback = callback(new Cookie("MOGUM_OAUTH_REQUEST", "a".repeat(2049)), STATE);

        // when
        var restored = repository.loadAuthorizationRequest(callback);

        // then
        assertThat(restored).isNull();
    }

    @Test
    @DisplayName("중복된 쿠키는 복원하지 않는다")
    void rejectsDuplicateCookies() {
        // given
        var repository = repository(NOW);
        var cookie = savedCookie(VERIFIER);
        var request = callback(cookie, STATE);
        request.setCookies(cookie, cookie);

        // when
        var restored = repository.loadAuthorizationRequest(request);

        // then
        assertThat(restored).isNull();
    }

    private Cookie savedCookie(String verifier) {
        var saved = new ObjectMapper().createObjectNode().put("state", STATE).put("issuedAt", NOW.getEpochSecond());
        if (verifier != null) saved.put("code_verifier", verifier);
        return new Cookie("MOGUM_OAUTH_REQUEST", Base64.getUrlEncoder().withoutPadding()
                .encodeToString(saved.toString().getBytes(StandardCharsets.UTF_8)));
    }

    @ParameterizedTest(name = "쿠키 값 [{0}]")
    @ValueSource(strings = {"garbage", "e30", "rO0ABXNy", "eyJzdGF0ZSI6MX0", ""})
    @DisplayName("망가진 쿠키는 예외 없이 없던 요청으로 처리한다")
    void treatsMalformedCookieAsMissingRequest(String value) {
        // given
        var repository = repository(NOW);
        var callback = callback(new Cookie("MOGUM_OAUTH_REQUEST", value), STATE);

        // when
        var restored = repository.loadAuthorizationRequest(callback);

        // then
        assertThat(restored).isNull();
    }

    @ParameterizedTest(name = "거절 조건 {0}")
    @ValueSource(strings = {"expired", "future", "state-mismatch", "missing-cookie"})
    @DisplayName("만료된 쿠키와 미래 시각과 불일치 state 및 없는 쿠키를 거절한다")
    void rejectsExpiredFutureMismatchedOrMissingCookie(String condition) {
        // given
        var response = new MockHttpServletResponse();
        repository(NOW).saveAuthorizationRequest(authorization(), new MockHttpServletRequest(), response);
        var cookie = response.getCookie("MOGUM_OAUTH_REQUEST");
        var instant = switch (condition) {
            case "expired" -> NOW.plusSeconds(601);
            case "future" -> NOW.minusSeconds(1);
            default -> NOW;
        };
        var repository = repository(instant);
        var request = condition.equals("missing-cookie") ? new MockHttpServletRequest()
                : callback(cookie, condition.equals("state-mismatch") ? "different" : STATE);

        // when
        var restored = repository.loadAuthorizationRequest(request);

        // then
        assertThat(restored).isNull();
    }

    private CookieAuthorizationRequestRepository repository(Instant instant) {
        return new CookieAuthorizationRequestRepository(new InMemoryClientRegistrationRepository(registration),
                new ObjectMapper(), Clock.fixed(instant, ZoneOffset.UTC));
    }

    private OAuth2AuthorizationRequest authorization() {
        return OAuth2AuthorizationRequest.authorizationCode().authorizationUri(registration.getProviderDetails().getAuthorizationUri())
                .clientId(registration.getClientId()).redirectUri(registration.getRedirectUri()).scopes(registration.getScopes())
                .state(STATE).attributes(attributes -> {
                    attributes.put("registration_id", "google");
                    attributes.put("code_verifier", VERIFIER);
                }).build();
    }

    private MockHttpServletRequest callback(Cookie cookie, String state) {
        var request = new MockHttpServletRequest();
        request.setCookies(cookie);
        request.setParameter("state", state);
        return request;
    }
}
