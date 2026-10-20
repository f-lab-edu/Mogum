package com.mogum.support;

import jakarta.servlet.http.Cookie;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** 실제 앱 저장소에 사용자 ID 하나만 저장한다. Spring Session 기본 쿠키는 Base64 인코딩된다. */
public final class TestSessions {
    private final SessionRepository<Session> repository;

    @SuppressWarnings("unchecked")
    public TestSessions(JdbcIndexedSessionRepository repository) {
        this.repository = (SessionRepository<Session>) (SessionRepository<?>) repository;
    }

    public Cookie create(long userId) {
        var session = repository.createSession();
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME,
                Long.toString(userId));
        repository.save(session);
        return new Cookie("SESSION", Base64.getEncoder().encodeToString(
                session.getId().getBytes(StandardCharsets.UTF_8)));
    }

    public String id(Cookie cookie) {
        return new String(Base64.getDecoder().decode(cookie.getValue()), StandardCharsets.UTF_8);
    }
}
