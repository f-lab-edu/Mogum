package com.mogum.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class CreditPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    @DisplayName("한 달 몫은 application 설정의 단일 키에서 읽는다")
    void readsMonthlyAmountFromApplicationConfiguration() {
        // when
        runner.run(context -> {
            // then
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(CreditProperties.class).monthlyAmount())
                    .isEqualTo(context.getEnvironment().getRequiredProperty("app.credit.monthly-amount", Integer.class))
                    .isPositive();
        });
    }

    @Test
    @DisplayName("한 달 몫 설정을 바꾸면 바인딩 값도 바뀐다")
    void bindsConfiguredMonthlyAmount() {
        // given
        var configured = runner.withPropertyValues("app.credit.monthly-amount=37");
        // when
        configured.run(context -> {
            // then
            assertThat(context.getBean(CreditProperties.class).monthlyAmount()).isEqualTo(37);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CreditProperties.class)
    static class PropertiesConfiguration { }
}
