package com.mogum.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void 로컬_기본값은_Vite_출처와_빈_쿠키_도메인이다() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            var properties = context.getBean(WebProperties.class);
            assertEquals("http://localhost:5173", properties.origin());
            assertEquals("", properties.cookieDomain());
        });
    }

    @Test
    void 설정_키로_단일_출처와_쿠키_도메인을_바인딩한다() {
        runner.withPropertyValues("app.web.origin=https://mogum.example", "app.web.cookie-domain=mogum.example")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var properties = context.getBean(WebProperties.class);
                    assertEquals("https://mogum.example", properties.origin());
                    assertEquals("mogum.example", properties.cookieDomain());
                });
    }

    @Test
    void 환경_변수로_출처와_쿠키_도메인을_덮어쓴다() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("test-env", Map.of(
                        "APP_WEB_ORIGIN", "https://mogum.example",
                        "APP_WEB_COOKIE_DOMAIN", "mogum.example"))))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var properties = context.getBean(WebProperties.class);
                    assertEquals("https://mogum.example", properties.origin());
                    assertEquals("mogum.example", properties.cookieDomain());
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "https://*.mogum.example", "https://mogum.example,https://evil.example",
            "https://mogum.example/", "https://mogum.example/path", "https://mogum.example?next=x",
            "https://user@mogum.example", "", "mogum.example"})
    void 출처는_경로와_패턴과_목록_없는_단일_HTTP_출처여야_한다(String origin) {
        runner.withPropertyValues("app.web.origin=" + origin).run(context ->
                assertNotNull(context.getStartupFailure()));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WebProperties.class)
    static class PropertiesConfiguration { }
}
