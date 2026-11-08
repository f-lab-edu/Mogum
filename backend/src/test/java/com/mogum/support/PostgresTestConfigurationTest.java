package com.mogum.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresTestConfigurationTest {
    @Test
    @DisplayName("접속 정보만 제공하므로 컨텍스트가 닫혀도 공유 컨테이너는 살아 있다")
    void keepsContainerAliveAfterContextCloses() throws Exception {
        // given
        var initializer = new PostgresTestConfiguration.ConnectionInitializer();
        try (var context = new GenericApplicationContext()) {
            initializer.initialize(context);
            context.refresh();
            assertThat(context.getEnvironment().getProperty("spring.datasource.url"))
                    .isEqualTo(PostgresTestConfiguration.POSTGRES.getJdbcUrl());
            assertThat(context.getBeansOfType(org.testcontainers.containers.Container.class)).isEmpty();
        }

        // when
        var postgres = PostgresTestConfiguration.POSTGRES;
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword())) {
            // then
            assertThat(postgres.isRunning()).isTrue();
            assertThat(connection.isValid(2)).isTrue();
        }
    }
}
