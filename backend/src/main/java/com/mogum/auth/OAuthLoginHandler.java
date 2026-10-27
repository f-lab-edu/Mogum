package com.mogum.auth;

import com.mogum.account.service.ExternalAccount;
import com.mogum.account.service.LoginResult;
import com.mogum.account.service.LoginService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.session.FindByIndexNameSessionRepository;

import java.io.IOException;

public final class OAuthLoginHandler implements AuthenticationSuccessHandler, AuthenticationFailureHandler {
    private static final Logger log = LoggerFactory.getLogger(OAuthLoginHandler.class);
    private final LoginService login;
    private final GoogleAccountMapper accounts;
    private final String origin;
    private final CookieAuthorizationRequestRepository requests;

    public OAuthLoginHandler(LoginService login, GoogleAccountMapper accounts, String origin,
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
                fail(request, response, "login_failed", failureReason(invalid));
                return;
            }
            switch (login.login(account)) {
                case LoginResult.Success success -> {
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
                    log.info("login result=success userId={}", success.userId());
                }
                case LoginResult.Rejected rejected ->
                        fail(request, response, rejected.reason() == LoginResult.RejectionReason.NOT_ALLOWED
                                ? "not_allowed" : "login_failed", switch (rejected.reason()) {
                                    case NOT_ALLOWED -> "not_allowed";
                                    case EMAIL_NOT_VERIFIED -> "email_not_verified";
                                });
            }
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        fail(request, response, "login_failed", failureReason(exception));
    }

    // error code도 콜백/프로바이더가 보낸 원문일 수 있으므로 알려진 코드만 고정 문자열로 바꾼다.
    private static String failureReason(AuthenticationException exception) {
        if (!(exception instanceof OAuth2AuthenticationException oauth)) return "unknown";
        return switch (oauth.getError().getErrorCode()) {
            case "authorization_request_not_found" -> "authorization_request_not_found";
            case "invalid_state_parameter" -> "invalid_state_parameter";
            case "access_denied" -> "access_denied";
            case "invalid_request" -> "invalid_request";
            case "invalid_user_info" -> "invalid_user_info";
            case "invalid_grant" -> "invalid_grant";
            case "invalid_token_response" -> "invalid_token_response";
            case "invalid_user_info_response" -> "invalid_user_info_response";
            default -> "unknown";
        };
    }

    private void fail(HttpServletRequest request, HttpServletResponse response, String error, String reason) throws IOException {
        requests.saveAuthorizationRequest(null, request, response);
        SecurityContextHolder.clearContext();
        response.sendRedirect(origin + "/login?error=" + error);
        if (reason.equals("invalid_grant") || reason.equals("invalid_token_response") || reason.equals("invalid_user_info_response")) {
            log.warn("login result={} reason={}", error, reason);
        } else {
            log.info("login result={} reason={}", error, reason);
        }
    }
}
