package com.mogum.config;

import com.mogum.account.domain.UserRole;
import com.mogum.account.repository.UserRepository;
import com.mogum.account.service.LoginService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.time.Clock;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AuthPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withBean(UserRepository.class, () -> mock(UserRepository.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(LoginService.class);

    @Test
    void 기본_관리자_목록은_비어_있고_로그인_서비스에_전달한다() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBean(AuthProperties.class).adminEmails().isEmpty());
            assertEquals(UserRole.USER, context.getBean(LoginService.class).roleFor("admin@example.com"));
        });
    }

    @Test
    void 쉼표_목록을_바인딩하고_역할_계산에_전달한다() {
        runner.withPropertyValues("app.auth.admin-emails= ADMIN@example.com , second@example.com")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(2, context.getBean(AuthProperties.class).adminEmails().size());
                    var login = context.getBean(LoginService.class);
                    assertEquals(UserRole.ADMIN, login.roleFor("admin@example.com"));
                    assertEquals(UserRole.ADMIN, login.roleFor(" SECOND@example.com "));
                    assertEquals(UserRole.USER, login.roleFor("other@example.com"));
                });
    }

    @Test
    void 빈_설정_값은_빈_목록으로_바인딩한다() {
        runner.withPropertyValues("app.auth.admin-emails=").run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBean(AuthProperties.class).adminEmails().isEmpty());
        });
    }

    @Test
    void 환경_변수로_관리자_목록을_덮어쓴다() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("test-env", Map.of(
                        "APP_AUTH_ADMIN_EMAILS", "env-admin@example.com,second@example.com"))))
                .withPropertyValues("app.auth.admin-emails=").run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(UserRole.ADMIN, context.getBean(LoginService.class).roleFor("env-admin@example.com"));
                    assertEquals(2, context.getBean(AuthProperties.class).adminEmails().size());
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AuthProperties.class)
    static class PropertiesConfiguration { }
}
