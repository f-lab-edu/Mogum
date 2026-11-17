package com.mogum.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerPropertiesTest {
    private final ApplicationContextRunner applicationContextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    @DisplayName("워커 수와 시한 및 종료 임시 값은 application 설정에서 읽는다")
    void bindsProvisionalSettings() {
        // when
        applicationContextRunner.run(context -> {
            // then
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(WorkerProperties.class)).isEqualTo(new WorkerProperties(
                    Duration.ofMinutes(5), Duration.ofSeconds(30), 2, Duration.ofSeconds(1),
                    2, Duration.ofSeconds(1), Duration.ofSeconds(100)));
            assertThat(Binder.get(context.getEnvironment())
                    .bind("spring.lifecycle.timeout-per-shutdown-phase", Duration.class)
                    .get())
                    .isGreaterThan(Duration.ofSeconds(120));
        });
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"app.worker.count=-1", "app.worker.poll-interval=0s",
            "app.worker.poll-interval=-1s", "app.worker.lease-duration=0s",
            "app.worker.renewal-interval=0s", "app.worker.renewal-interval=5m",
            "app.worker.renewal-interval=6m", "app.worker.max-retries=-1",
            "app.worker.retry-delay=-1s", "app.worker.shutdown-wait=0s",
            "app.worker.shutdown-wait=120s", "app.worker.shutdown-wait=130s",
            "app.worker.shutdown-wait=-1s"})
    @DisplayName("시한 및 종료 대기 여유를 깨거나 음수인 설정은 기동을 거부한다")
    void rejectsInvalidSettings(String property) {
        // given
        var invalid = applicationContextRunner.withPropertyValues(property);

        // when
        invalid.run(context -> {
            // then
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test
    @DisplayName("워커 수 0과 자동 다시 시도 간격 0은 허용한다")
    void acceptsDisabledWorkersAndImmediateRetry() {
        // given
        var disabled = applicationContextRunner.withPropertyValues("app.worker.count=0", "app.worker.retry-delay=0s");

        // when
        disabled.run(context -> {
            // then
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(WorkerProperties.class).count()).isZero();
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WorkerProperties.class)
    static class PropertiesConfiguration { }
}
