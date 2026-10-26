package com.mogum.auth;

import com.mogum.account.repository.UserRepository;
import com.mogum.common.logging.RequestLogFilter;
import org.slf4j.MDC;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/** 인증은 요청 안에만 두고, JDBC 세션에는 사용자 ID 문자열만 남긴다. */
public final class SessionUserFilter extends OncePerRequestFilter {
    private final UserRepository users;
    private final AuthenticationEntryPoint entryPoint;

    public SessionUserFilter(UserRepository users, AuthenticationEntryPoint entryPoint) {
        this.users = users;
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        var session = request.getSession(false);
        if (session != null) {
            Long userId = userId(session.getAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME));
            var user = userId == null ? null : users.findById(userId).orElse(null);
            if (user == null) {
                session.invalidate();
                entryPoint.commence(request, response, new InsufficientAuthenticationException("Session user missing"));
                return;
            }
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))));
            SecurityContextHolder.setContext(context);
            MDC.put(RequestLogFilter.USER_ID, user.getId().toString());
        }
        chain.doFilter(request, response);
    }

    private static Long userId(Object attribute) {
        if (!(attribute instanceof String value)) return null;
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
