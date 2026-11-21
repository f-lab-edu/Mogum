package com.mogum.auth;

import java.io.IOException;
import java.util.List;

import org.slf4j.MDC;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import com.mogum.account.repository.UserRepository;
import com.mogum.common.logging.RequestLogFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** 인증은 요청 안에만 두고, JDBC 세션에는 회원 ID 또는 신청 전 구글 신원만 남긴다. */
@RequiredArgsConstructor
public final class SessionUserFilter extends OncePerRequestFilter {
	private final UserRepository userRepository;
	private final AuthenticationEntryPoint authenticationEntryPoint;

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
		FilterChain chain) throws ServletException, IOException {
		var session = request.getSession(false);
		if (session != null) {
			Object owner = session.getAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME);
			Object identity = session.getAttribute(AccountPrincipal.NOT_APPLIED_SESSION_ATTRIBUTE);
			AccountPrincipal principal = null;

			if (owner != null && identity == null) {
				Long userId = userId(owner);
				var user = userId == null ? null : userRepository.findById(userId).orElse(null);
				if (user != null) {
					principal = new AccountPrincipal.Member(
						user.getId(), user.getEmail(), user.getName(), user.getStatus());
				}
			} else if (owner == null && identity instanceof AccountPrincipal.NotApplied account
				&& "google".equals(account.provider()) && hasText(account.subject())
				&& hasText(account.email()) && hasText(account.name())) {
				principal = account;
			}

			if (principal == null) {
				session.invalidate();
				authenticationEntryPoint.commence(request, response,
					new InsufficientAuthenticationException("Session user missing"));
				return;
			}

			var context = SecurityContextHolder.createEmptyContext();
			context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
				List.of()));
			SecurityContextHolder.setContext(context);
			if (principal instanceof AccountPrincipal.Member member) {
				MDC.put(RequestLogFilter.USER_ID, Long.toString(member.userId()));
			}
		}

		chain.doFilter(request, response);
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private static Long userId(Object attribute) {
		if (!(attribute instanceof String value))
			return null;

		try {
			long id = Long.parseLong(value);
			return id > 0 ? id : null;
		} catch (NumberFormatException exception) {
			return null;
		}
	}
}
