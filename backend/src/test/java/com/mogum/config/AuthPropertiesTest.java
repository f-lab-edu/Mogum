package com.mogum.config;

import com.mogum.account.domain.UserRole;
import com.mogum.account.repository.FakeUserRepository;
import com.mogum.account.repository.UserRepository;
import com.mogum.account.service.LoginService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withBean(UserRepository.class, FakeUserRepository::new)
            .withBean(LoginService.class);

    @Test
    @DisplayName("기본 관리자 목록은 비어 있고 로그인 서비스에 전달한다")
    void passesEmptyDefaultAllowlistToLoginService() {
        // when
        runner.run(context -> {
            // then
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AuthProperties.class).adminEmails()).isEmpty();
            assertThat(context.getBean(LoginService.class).roleFor("admin@example.com")).isEqualTo(UserRole.USER);
        });
    }

    @Test
    @DisplayName("쉼표 목록을 바인딩하고 역할 계산에 전달한다")
    void bindsCommaSeparatedAllowlistToRoleCalculation() {
        // given
        var configured = runner.withPropertyValues("app.auth.admin-emails= ADMIN@example.com , second@example.com");

        // when
        configured.run(context -> {
            // then
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AuthProperties.class).adminEmails()).hasSize(2);
            var login = context.getBean(LoginService.class);
            assertThat(login.roleFor("admin@example.com")).isEqualTo(UserRole.ADMIN);
            assertThat(login.roleFor(" SECOND@example.com ")).isEqualTo(UserRole.ADMIN);
            assertThat(login.roleFor("other@example.com")).isEqualTo(UserRole.USER);
        });
    }

    @Test
    @DisplayName("빈 설정 값은 빈 목록으로 바인딩한다")
    void bindsEmptySettingToEmptyAllowlist() {
        // given
        var configured = runner.withPropertyValues("app.auth.admin-emails=");

        // when
        configured.run(context -> {
            // then
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AuthProperties.class).adminEmails()).isEmpty();
        });
    }

    @Test
    @DisplayName("환경 변수로 관리자 목록을 덮어쓴다")
    void overridesAllowlistFromEnvironment() {
        // given
        var configured = runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("test-env", Map.of(
                        "APP_AUTH_ADMIN_EMAILS", "env-admin@example.com,second@example.com"))))
                .withPropertyValues("app.auth.admin-emails=");

        // when
        configured.run(context -> {
            // then
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(LoginService.class).roleFor("env-admin@example.com")).isEqualTo(UserRole.ADMIN);
            assertThat(context.getBean(AuthProperties.class).adminEmails()).hasSize(2);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AuthProperties.class)
    static class PropertiesConfiguration { }
}
