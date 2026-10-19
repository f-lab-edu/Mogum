package com.mogum;

import com.mogum.core.TransactionRunner;
import com.mogum.core.account.Login;
import com.mogum.core.account.UserRepository;
import com.mogum.core.account.UserIdentityRepository;
import com.mogum.core.account.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.time.Clock;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AuthConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AuthConfiguration.class)
            .withBean(UserRepository.class, () -> mock(UserRepository.class))
            .withBean(UserIdentityRepository.class, () -> mock(UserIdentityRepository.class))
            .withBean(TransactionRunner.class, () -> mock(TransactionRunner.class))
            .withBean(Clock.class, Clock::systemUTC);

    @Test
    void 기본_관리자_목록은_비어_있고_유스케이스를_등록한다() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBean(AuthProperties.class).adminEmails().isEmpty());
            assertEquals(UserRole.USER, context.getBean(Login.class).roleFor("admin@example.com"));
        });
    }

    @Test
    void 쉼표_목록을_바인딩하고_역할_계산에_전달한다() {
        runner.withPropertyValues("app.auth.admin-emails= ADMIN@example.com , second@example.com")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(2, context.getBean(AuthProperties.class).adminEmails().size());
                    var login = context.getBean(Login.class);
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
                    assertEquals(UserRole.ADMIN, context.getBean(Login.class).roleFor("env-admin@example.com"));
                    assertEquals(2, context.getBean(AuthProperties.class).adminEmails().size());
                });
    }
}
