package com.mogum.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.json.JsonAssert;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.support.TestRequests;
import com.mogum.support.TestSessions;

import jakarta.servlet.http.Cookie;

abstract class SecurityScenario {
	@Autowired
	TestRequests testRequests;
	@Autowired
	UserRepository userRepository;
	@Autowired
	JdbcTemplate jdbcTemplate;
	@Autowired
	JdbcIndexedSessionRepository jdbcIndexedSessionRepository;

	Cookie session() {
		var user = userRepository.save(UserFixtures.user("google", "session-sub", "session@example.com"));
		return new TestSessions(jdbcIndexedSessionRepository).create(user.getId());
	}

	Cookie approvedSession() {
		var user = userRepository.save(UserFixtures.approved("google", "session-sub", "session@example.com"));
		return new TestSessions(jdbcIndexedSessionRepository).create(user.getId());
	}

	RestTestClient client(Cookie cookie) {
		return testRequests.anonymous().mutate().defaultCookie(cookie.getName(), cookie.getValue()).build();
	}

	static void assertError(EntityExchangeResult<String> result, int status, String code) {
		assertThat(result.getStatus().value()).isEqualTo(status);
		assertThat(result.getResponseHeaders().getFirst("Location")).isNull();
		assertThat(result.getResponseHeaders().getFirst("Content-Type")).startsWith("application/json");
		assertThat(result.getResponseHeaders().getFirst("Retry-After")).isNull();

		String message = switch (code) {
			case "UNAUTHORIZED" -> "로그인이 필요합니다";
			case "CSRF_INVALID", "INVALID_REQUEST" -> "요청을 확인할 수 없습니다";
			case "NOT_FOUND" -> "찾을 수 없습니다";
			case "METHOD_NOT_ALLOWED" -> "지원하지 않는 요청 방식입니다";
			default -> "요청을 처리할 수 없습니다";
		};
		JsonAssert.comparator(JsonCompareMode.STRICT).assertIsMatch(
			"{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}", result.getResponseBody());
		assertThat(result.getResponseBody()).doesNotContain("private servlet failure", "request log test failure");
	}
}
