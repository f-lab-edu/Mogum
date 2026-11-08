package com.mogum.support;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 테스트 JVM에서 한 번 시작하는 공유 DB다. 컨테이너 단독 테스트는 POSTGRES를 직접 쓰고,
 * 레포지토리/통합 묶음은 {@link ConnectionInitializer}로 접속 정보만 제공한다.
 * 컨테이너를 빈이나 @Container로 등록하지 않아 클래스/컨텍스트 종료 때 멈추지 않으며,
 * JVM 종료 후 Ryuk이 정리한다. DB 내용은 {@link DatabaseCleaner}로 테스트마다 비운다.
 * Boot 4.1.1의 @ImportTestcontainers는 컨테이너 빈을 등록하고 종료 때 close하므로 쓰지 않는다.
 */
public final class PostgresTestConfiguration {
    private PostgresTestConfiguration() {
    }

    public static final PostgreSQLContainer POSTGRES =
            // 공용 통합 컨텍스트 · 레포지토리 슬라이스 · 마이그레이션 검사의 풀이 함께 쓰는 테스트 DB다.
            new PostgreSQLContainer("pgvector/pgvector:pg17")
                    .withCommand("postgres", "-c", "max_connections=200");

    static {
        POSTGRES.start();
    }

    public static final class ConnectionInitializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            TestPropertyValues.of(
                    "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                    "spring.datasource.username=" + POSTGRES.getUsername(),
                    "spring.datasource.password=" + POSTGRES.getPassword()).applyTo(context);
        }
    }
}
