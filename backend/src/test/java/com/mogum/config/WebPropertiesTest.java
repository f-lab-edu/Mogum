package com.mogum.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WebPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    @DisplayName("로컬 기본값은 Vite 출처와 빈 쿠키 도메인이다")
    void defaultsToViteOriginAndHostOnlyCookie() {
        // when
        runner.run(context -> {
            // then
            assertThat(context).hasNotFailed();
            var properties = context.getBean(WebProperties.class);
            assertThat(properties.origin()).isEqualTo("http://localhost:5173");
            assertThat(properties.cookieDomain()).isEmpty();
        });
    }

    @Test
    @DisplayName("설정 키로 단일 출처와 쿠키 도메인을 바인딩한다")
    void bindsOriginAndCookieDomain() {
        // given
        var configured = runner.withPropertyValues("app.web.origin=https://mogum.example", "app.web.cookie-domain=mogum.example");

        // when
        configured.run(context -> {
            // then
            assertThat(context).hasNotFailed();
            var properties = context.getBean(WebProperties.class);
            assertThat(properties.origin()).isEqualTo("https://mogum.example");
            assertThat(properties.cookieDomain()).isEqualTo("mogum.example");
        });
    }

    @Test
    @DisplayName("환경 변수로 출처와 쿠키 도메인을 덮어쓴다")
    void overridesOriginAndDomainFromEnvironment() {
        // given
        var configured = runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("test-env", Map.of(
                        "APP_WEB_ORIGIN", "https://mogum.example",
                        "APP_WEB_COOKIE_DOMAIN", "mogum.example"))));

        // when
        configured.run(context -> {
            // then
            assertThat(context).hasNotFailed();
            var properties = context.getBean(WebProperties.class);
            assertThat(properties.origin()).isEqualTo("https://mogum.example");
            assertThat(properties.cookieDomain()).isEqualTo("mogum.example");
        });
    }

    @ParameterizedTest(name = "출처 [{0}]")
    @ValueSource(strings = {"*", "https://*.mogum.example", "https://mogum.example,https://evil.example",
            "https://mogum.example/", "https://mogum.example/path", "https://mogum.example?next=x",
            "https://user@mogum.example", "", "mogum.example"})
    @DisplayName("출처는 경로와 패턴과 목록 없는 단일 HTTP 출처여야 한다")
    void rejectsInvalidSingleHttpOrigin(String origin) {
        // given
        var configured = runner.withPropertyValues("app.web.origin=" + origin);

        // when
        configured.run(context -> {
            // then
            assertThat(context).hasFailed();
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WebProperties.class)
    static class PropertiesConfiguration { }
}
