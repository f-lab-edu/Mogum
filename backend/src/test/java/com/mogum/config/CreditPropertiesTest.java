package com.mogum.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class CreditPropertiesTest {
    @Test
    @DisplayName("종류별 크레딧은 각각의 설정 키에서 임시 값 1을 읽는다")
    void readsTemporaryJobAmounts() {
        // when
        runner.run(context -> {
            // then
            var credit = context.getBean(CreditProperties.class);
            assertThat(credit.newCourse()).isEqualTo(1);
            assertThat(credit.newRoadmap()).isEqualTo(1);
            assertThat(credit.planRoadmapCourse()).isEqualTo(1);
            assertThat(credit.replanCourse()).isEqualTo(1);
            assertThat(credit.replanRoadmap()).isEqualTo(1);
            assertThat(credit.material()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("종류별 크레딧은 서로 다른 설정 값을 각각 바인딩한다")
    void bindsEachJobAmount() {
        // given
        var configured = runner.withPropertyValues("app.credit.new-course=2", "app.credit.new-roadmap=3",
                "app.credit.plan-roadmap-course=4", "app.credit.replan-course=5", "app.credit.replan-roadmap=6",
                "app.credit.material=7");

        // when
        configured.run(context -> {
            // then
            var credit = context.getBean(CreditProperties.class);
            assertThat(credit.newCourse()).isEqualTo(2);
            assertThat(credit.newRoadmap()).isEqualTo(3);
            assertThat(credit.planRoadmapCourse()).isEqualTo(4);
            assertThat(credit.replanCourse()).isEqualTo(5);
            assertThat(credit.replanRoadmap()).isEqualTo(6);
            assertThat(credit.material()).isEqualTo(7);
        });
    }

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
