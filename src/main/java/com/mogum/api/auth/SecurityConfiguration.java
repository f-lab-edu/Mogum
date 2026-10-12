package com.mogum.api.auth;

import com.mogum.WebProperties;
import com.mogum.api.ApiError;
import com.mogum.core.TransactionRunner;
import com.mogum.core.account.GoogleLogin;
import com.mogum.core.account.UserRepository;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.session.autoconfigure.DefaultCookieSerializerCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.util.Assert;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Clock;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebProperties.class)
public class SecurityConfiguration {
    @Bean
    public DiscardingOAuth2AuthorizedClients discardedGoogleTokens(ClientRegistrationRepository registrations) {
        var google = registrations.findByRegistrationId("google");
        Assert.hasText(google.getClientId(), "GOOGLE_CLIENT_ID must be set");
        Assert.hasText(google.getClientSecret(), "GOOGLE_CLIENT_SECRET must be set");
        return new DiscardingOAuth2AuthorizedClients();
    }

    @Bean
    public DefaultCookieSerializerCustomizer sessionCookieSecurity() {
        // ServletContext의 기본 쿠키 설정과 무관하게 발급/만료 쿠키에 같은 정책을 적용한다.
        return cookie -> {
            cookie.setUseHttpOnlyCookie(true);
            cookie.setUseSecureCookie(true);
            cookie.setSameSite("Lax");
        };
    }

    @Bean
    public SecurityFilterChain apiSecurity(HttpSecurity http, UserRepository users,
                                           TransactionRunner transactions, ObjectMapper mapper,
                                           WebProperties web, GoogleLogin login, Clock clock,
                                           ClientRegistrationRepository registrations,
                                           DiscardingOAuth2AuthorizedClients clients) throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, exception) ->
                writeError(response, mapper, 401, "UNAUTHORIZED", "로그인이 필요합니다");
        var tokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
        tokens.setCookieCustomizer(cookie -> {
            cookie.secure(true).sameSite("Lax").path("/");
            if (!web.cookieDomain().isEmpty()) cookie.domain(web.cookieDomain());
        });
        var tokenHandler = new CsrfTokenRequestAttributeHandler();
        // SPA는 쿠키 원본을 헤더로 보내며, 첫 GET /api/me(401 포함)에서 쿠키를 발급한다.
        tokenHandler.setCsrfRequestAttributeName(null);
        var logoutRequest = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/logout");
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of(web.origin()));
        cors.setAllowCredentials(true);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        cors.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN"));
        var corsSource = new UrlBasedCorsConfigurationSource();
        corsSource.registerCorsConfiguration("/api/**", cors);
        var oauthRequests = new CookieAuthorizationRequestRepository(registrations, mapper, clock);
        var loginHandler = new GoogleLoginHandler(login, web.origin(), oauthRequests);
        var resolver = new DefaultOAuth2AuthorizationRequestResolver(registrations, "/auth");
        var getOnlyResolver = new OAuth2AuthorizationRequestResolver() {
            @Override
            public OAuth2AuthorizationRequest resolve(jakarta.servlet.http.HttpServletRequest request) {
                return "GET".equals(request.getMethod()) ? resolver.resolve(request) : null;
            }

            @Override
            public OAuth2AuthorizationRequest resolve(jakarta.servlet.http.HttpServletRequest request, String registrationId) {
                return "GET".equals(request.getMethod()) ? resolver.resolve(request, registrationId) : null;
            }
        };

        http.cors(configuration -> configuration.configurationSource(corsSource))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                // 기존 세션을 읽는 요청마다 새 로그인으로 판단해 ID/CSRF를 바꾸지 않는다.
                .sessionManagement(session -> session.requireExplicitAuthenticationStrategy(true))
                .oauth2Login(oauth -> oauth.loginPage("/auth/google")
                        .authorizedClientRepository(clients).authorizedClientService(clients)
                        .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(getOnlyResolver)
                                .authorizationRequestRepository(oauthRequests))
                        .redirectionEndpoint(endpoint -> endpoint.baseUri("/auth/google/callback"))
                        .successHandler(loginHandler).failureHandler(loginHandler))
                .addFilterBefore(new SessionUserFilter(users, transactions, unauthorized), CsrfFilter.class)
                .csrf(csrf -> csrf.csrfTokenRepository(tokens).csrfTokenRequestHandler(tokenHandler))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler((request, response, exception) -> {
                            var authentication = SecurityContextHolder.getContext().getAuthentication();
                            boolean anonymous = authentication == null || authentication instanceof AnonymousAuthenticationToken;
                            if (anonymous && !logoutRequest.matches(request)) {
                                unauthorized.commence(request, response,
                                        new InsufficientAuthenticationException("Login required"));
                            } else if (exception instanceof CsrfException) {
                                writeError(response, mapper, 403, "CSRF_INVALID", "요청을 확인할 수 없습니다");
                            } else {
                                writeError(response, mapper, 404, "NOT_FOUND", "찾을 수 없습니다");
                            }
                        }))
                .authorizeHttpRequests(requests -> requests.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/auth/google", "/auth/google/callback").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .logout(logout -> logout.logoutRequestMatcher(logoutRequest)
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)));
        return http.build();
    }

    private static void writeError(HttpServletResponse response, ObjectMapper mapper, int status,
                                    String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), new ApiError(code, message));
    }
}
