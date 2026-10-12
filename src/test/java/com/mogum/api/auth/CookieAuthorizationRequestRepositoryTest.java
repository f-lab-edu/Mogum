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
    private final ClientRegistration registration = ClientRegistration.withRegistrationId("google")
            .clientId("test-client").clientSecret("test-secret")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).scope("email")
            .authorizationUri("https://provider.example/authorize").tokenUri("https://provider.example/token")
            .userInfoUri("https://provider.example/userinfo").userNameAttributeName("sub")
            .redirectUri("http://localhost:5173/auth/google/callback").build();

    @Test
    void JSON은_state와_발급시각만_담고_설정에서_요청을_복원한다() {
        var repository = repository(NOW);
        var response = new MockHttpServletResponse();
        var request = new MockHttpServletRequest();
        repository.saveAuthorizationRequest(authorization(), request, response);
        assertNull(request.getSession(false));
        Cookie cookie = response.getCookie("MOGUM_OAUTH_REQUEST");
        assertNotNull(cookie);
        String json = new String(Base64.getUrlDecoder().decode(cookie.getValue()), StandardCharsets.UTF_8);
        assertTrue(json.contains(STATE));
        assertFalse(json.contains("redirect"));
        assertFalse(json.contains("secret"));
        var callback = callback(cookie, STATE);
        var restored = repository.loadAuthorizationRequest(callback);
        assertNotNull(restored);
        assertEquals(registration.getRedirectUri(), restored.getRedirectUri());
        assertEquals(registration.getClientId(), restored.getClientId());
        assertEquals("google", restored.getAttribute("registration_id"));
        var removed = new MockHttpServletResponse();
        assertNotNull(repository.removeAuthorizationRequest(callback, removed));
        assertEquals(0, removed.getCookie("MOGUM_OAUTH_REQUEST").getMaxAge());
        assertNull(callback.getSession(false));
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
                .state(STATE).attributes(attributes -> attributes.put("registration_id", "google")).build();
    }

    private MockHttpServletRequest callback(Cookie cookie, String state) {
        var request = new MockHttpServletRequest();
        request.setCookies(cookie);
        request.setParameter("state", state);
        return request;
    }
}
