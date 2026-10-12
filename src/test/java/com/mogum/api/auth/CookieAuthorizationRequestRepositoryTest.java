package com.mogum.api.auth;

import jakarta.servlet.http.Cookie;
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

import static org.junit.jupiter.api.Assertions.*;

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
    void JSON은_state와_발급시각과_PKCE_verifier만_담고_설정에서_요청을_복원한다() {
        var repository = repository(NOW);
        var response = new MockHttpServletResponse();
        var request = new MockHttpServletRequest();
        repository.saveAuthorizationRequest(authorization(), request, response);
        assertNull(request.getSession(false));
        Cookie cookie = response.getCookie("MOGUM_OAUTH_REQUEST");
        assertNotNull(cookie);
        String json = new String(Base64.getUrlDecoder().decode(cookie.getValue()), StandardCharsets.UTF_8);
        assertTrue(json.contains(STATE));
        var saved = new ObjectMapper().readTree(json);
        assertEquals(3, saved.size());
        assertEquals(VERIFIER, saved.get("code_verifier").asText());
        assertFalse(json.contains("redirect"));
        assertFalse(json.contains("secret"));
        var callback = callback(cookie, STATE);
        var restored = repository.loadAuthorizationRequest(callback);
        assertNotNull(restored);
        assertEquals(registration.getRedirectUri(), restored.getRedirectUri());
        assertEquals(registration.getClientId(), restored.getClientId());
        assertEquals("google", restored.getAttribute("registration_id"));
        assertEquals(VERIFIER, restored.getAttribute("code_verifier"));
        var removed = new MockHttpServletResponse();
        assertNotNull(repository.removeAuthorizationRequest(callback, removed));
        assertEquals(0, removed.getCookie("MOGUM_OAUTH_REQUEST").getMaxAge());
        assertNull(callback.getSession(false));
    }

    @ParameterizedTest
    @ValueSource(ints = {43, 128})
    void RFC7636_verifier의_최소와_최대_길이를_복원한다(int length) {
        var cookie = savedCookie("a".repeat(length));
        var restored = repository(NOW).loadAuthorizationRequest(callback(cookie, STATE));
        assertNotNull(restored);
        assertEquals("a".repeat(length), restored.getAttribute("code_verifier"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "empty", "short", "long", "space", "padding", "unicode"})
    void 없거나_RFC7636_형식이_아닌_verifier는_복원하지_않는다(String condition) {
        String verifier = switch (condition) {
            case "missing" -> null;
            case "empty" -> "";
            case "short" -> "a".repeat(42);
            case "long" -> "a".repeat(129);
            case "space" -> "a".repeat(43) + " ";
            case "padding" -> "a".repeat(43) + "=";
            default -> "a".repeat(43) + "가";
        };
        assertNull(repository(NOW).loadAuthorizationRequest(callback(savedCookie(verifier), STATE)));
    }

    @Test
    void 길이_상한을_넘거나_중복된_쿠키는_복원하지_않는다() {
        assertNull(repository(NOW).loadAuthorizationRequest(
                callback(new Cookie("MOGUM_OAUTH_REQUEST", "a".repeat(2049)), STATE)));
        var cookie = savedCookie(VERIFIER);
        var request = callback(cookie, STATE);
        request.setCookies(cookie, cookie);
        assertNull(repository(NOW).loadAuthorizationRequest(request));
    }

    private Cookie savedCookie(String verifier) {
        var saved = new ObjectMapper().createObjectNode().put("state", STATE).put("issuedAt", NOW.getEpochSecond());
        if (verifier != null) saved.put("code_verifier", verifier);
        return new Cookie("MOGUM_OAUTH_REQUEST", Base64.getUrlEncoder().withoutPadding()
                .encodeToString(saved.toString().getBytes(StandardCharsets.UTF_8)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"garbage", "e30", "rO0ABXNy", "eyJzdGF0ZSI6MX0", ""})
    void 망가진_쿠키는_예외_없이_없던_요청으로_처리한다(String value) {
        assertNull(repository(NOW).loadAuthorizationRequest(callback(new Cookie("MOGUM_OAUTH_REQUEST", value), STATE)));
    }

    @Test
    void 만료된_쿠키와_미래_시각과_불일치_state와_없는_쿠키를_거절한다() {
        var response = new MockHttpServletResponse();
        repository(NOW).saveAuthorizationRequest(authorization(), new MockHttpServletRequest(), response);
        var cookie = response.getCookie("MOGUM_OAUTH_REQUEST");
        assertNull(repository(NOW.plusSeconds(601)).loadAuthorizationRequest(callback(cookie, STATE)));
        assertNull(repository(NOW.minusSeconds(1)).loadAuthorizationRequest(callback(cookie, STATE)));
        assertNull(repository(NOW).loadAuthorizationRequest(callback(cookie, "different")));
        assertNull(repository(NOW).loadAuthorizationRequest(new MockHttpServletRequest()));
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
