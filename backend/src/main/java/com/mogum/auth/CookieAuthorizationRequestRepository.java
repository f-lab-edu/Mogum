package com.mogum.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;

/** 브라우저의 데이터에서 state/시각/PKCE verifier만 읽는다. 나머지 요청은 서버 설정으로 복원한다. */
public final class CookieAuthorizationRequestRepository implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {
    private static final String COOKIE_NAME = "MOGUM_OAUTH_REQUEST";
    private static final String CLEARED = CookieAuthorizationRequestRepository.class.getName() + ".cleared";
    private static final int MAX_AGE_SECONDS = 600;
    private final ClientRegistrationRepository registrations;
    private final ObjectMapper mapper;
    private final Clock clock;

    public CookieAuthorizationRequestRepository(ClientRegistrationRepository registrations, ObjectMapper mapper, Clock clock) {
        this.registrations = registrations;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        String value = null;
        for (Cookie cookie : request.getCookies()) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                if (value != null) return null;
                value = cookie.getValue();
            }
        }
        if (value == null || value.length() > 2048) return null;
        try {
            var saved = mapper.readValue(Base64.getUrlDecoder().decode(value), SavedRequest.class);
            long age = clock.instant().getEpochSecond() - saved.issuedAt();
            if (saved.state() == null || saved.state().isBlank() || saved.state().length() > 128
                    || !saved.state().equals(request.getParameter("state")) || age < 0 || age >= MAX_AGE_SECONDS) return null;
            if (saved.codeVerifier() == null || !saved.codeVerifier().matches("[A-Za-z0-9._~-]{43,128}")) return null;
            var registration = registrations.findByRegistrationId("google");
            return OAuth2AuthorizationRequest.authorizationCode()
                    .authorizationUri(registration.getProviderDetails().getAuthorizationUri())
                    .clientId(registration.getClientId()).redirectUri(registration.getRedirectUri())
                    .scopes(registration.getScopes()).state(saved.state())
                    .attributes(attributes -> {
                        attributes.put("registration_id", "google");
                        attributes.put(PkceParameterNames.CODE_VERIFIER, saved.codeVerifier());
                    }).build();
        } catch (RuntimeException exception) {
            // 잘못된 Base64/JSON을 인증 실패로 다룬다. 쿠키 원문을 로그에 남기지 않는다.
            return null;
        }
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorization, HttpServletRequest request,
                                         HttpServletResponse response) {
        if (authorization == null && Boolean.TRUE.equals(request.getAttribute(CLEARED))) return;
        String value = authorization == null ? "" : Base64.getUrlEncoder().withoutPadding().encodeToString(
                mapper.writeValueAsString(new SavedRequest(authorization.getState(), clock.instant().getEpochSecond(),
                                authorization.getAttribute(PkceParameterNames.CODE_VERIFIER)))
                        .getBytes(StandardCharsets.UTF_8));
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true).secure(true).sameSite("Lax").path("/auth/google")
                .maxAge(authorization == null ? 0 : MAX_AGE_SECONDS).build().toString());
        if (authorization == null) request.setAttribute(CLEARED, true);
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request, HttpServletResponse response) {
        var authorization = loadAuthorizationRequest(request);
        saveAuthorizationRequest(null, request, response);
        return authorization;
    }

    private record SavedRequest(String state, long issuedAt, @JsonProperty("code_verifier") String codeVerifier) {}
}
