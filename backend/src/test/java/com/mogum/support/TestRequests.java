package com.mogum.support;

import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.web.servlet.client.RestTestClient;

/** 실제 포트에 연결된 클라이언트를 회원 세션과 SPA의 CSRF 쿠키/헤더로 준비한다. */
public final class TestRequests {
    private final RestTestClient client;
    private final TestSessions sessions;

    public TestRequests(RestTestClient client, JdbcIndexedSessionRepository repository) {
        this.client = client;
        this.sessions = new TestSessions(repository);
    }

    public RestTestClient anonymous() {
        return client;
    }

    public RestTestClient asUser(long userId) {
        var session = sessions.create(userId);
        var authenticated = client.mutate().defaultCookie(session.getName(), session.getValue()).build();
        var response = authenticated.get().uri("/api/me").exchange().returnResult();
        var token = response.getResponseCookies().getFirst("XSRF-TOKEN");
        if (token == null) throw new IllegalStateException("GET /api/me did not issue XSRF-TOKEN");
        return authenticated.mutate()
                .defaultCookie("XSRF-TOKEN", token.getValue())
                .defaultHeader("X-XSRF-TOKEN", token.getValue()).build();
    }
}
