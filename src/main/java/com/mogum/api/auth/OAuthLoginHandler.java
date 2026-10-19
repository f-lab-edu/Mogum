package com.mogum.api.auth;

import com.mogum.core.account.ExternalAccount;
import com.mogum.core.account.Login;
import com.mogum.core.account.LoginResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.session.FindByIndexNameSessionRepository;

import java.io.IOException;

public final class OAuthLoginHandler implements AuthenticationSuccessHandler, AuthenticationFailureHandler {
    private final Login login;
    private final GoogleAccountMapper accounts;
    private final String origin;
    private final CookieAuthorizationRequestRepository requests;

    public OAuthLoginHandler(Login login, GoogleAccountMapper accounts, String origin,
                              CookieAuthorizationRequestRepository requests) {
        this.login = login;
        this.accounts = accounts;
        this.origin = origin;
        this.requests = requests;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        try {
            ExternalAccount account;
            try {
                account = accounts.map(authentication);
            } catch (OAuth2AuthenticationException invalid) {
                fail(request, response, "login_failed");
                return;
            }
            var result = login.login(account);
            if (result instanceof LoginResult.Success success) {
                // 번호 변경은 JDBC 저장 시 반영된다. 변경 직후 invalidate하면 이전 DB 행을 다시 읽을 수 있다.
                var session = request.getSession(false);
                if (session != null) {
                    request.changeSessionId();
                    java.util.Collections.list(session.getAttributeNames()).forEach(session::removeAttribute);
                } else {
                    session = request.getSession(true);
                }
                session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME,
                        Long.toString(success.userId()));
                response.sendRedirect(origin + "/");
            } else {
                var rejected = (LoginResult.Rejected) result;
                fail(request, response, rejected.reason() == LoginResult.RejectionReason.NOT_ALLOWED
                        ? "not_allowed" : "login_failed");
            }
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        fail(request, response, "login_failed");
    }

    private void fail(HttpServletRequest request, HttpServletResponse response, String error) throws IOException {
        requests.saveAuthorizationRequest(null, request, response);
        SecurityContextHolder.clearContext();
        response.sendRedirect(origin + "/login?error=" + error);
    }
}
