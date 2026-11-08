package com.mogum.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.web.servlet.client.RestTestClient;

/** 테스트 전용 컴포넌트는 이 설정의 중첩 static 클래스로 두고 여기서 함께 등록한다. */
@TestConfiguration(proxyBeanMethods = false)
public class IntegrationTestConfiguration {
    @Bean
    public TestRequests testRequests(RestTestClient client, JdbcIndexedSessionRepository sessions) {
        return new TestRequests(client, sessions);
    }
}
