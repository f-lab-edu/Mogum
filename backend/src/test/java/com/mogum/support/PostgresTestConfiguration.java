package com.mogum.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * DB 테스트는 {@code @SpringBootTest} (또는 DB 슬라이스 어노테이션)를 붙이고
 * 이 클래스를 상속한다. 상속된 {@code @ServiceConnection}이 접속 정보를 제공한다.
 * 컨테이너는 테스트 JVM에서 한 번 시작하며 서로 다른 Spring 컨텍스트도 공유한다.
 * {@code @Testcontainers}/{@code @Container}나 컨테이너 빈으로 등록하지 않는다:
 * 클래스/컨텍스트 종료 때 공유 DB를 멈추지 않고, JVM 종료 후 Ryuk이 정리한다.
 * 새 묶음 어노테이션은 {@link ConnectionInitializer}로 접속 정보만 제공하고,
 * DB 내용은 {@link DatabaseCleaner}로 테스트마다 비운다. 기존 상속/필드 사용도 유지한다.
 * Boot 4.1.1 sources의 ContainerFieldsImporter는 @ImportTestcontainers의 필드를
 * 컨테이너 빈으로 등록하고 TestcontainersLifecycleBeanPostProcessor는 종료 때 close한다.
 * 따라서 그 경로 대신 @ContextConfiguration(initializers=...)를 사용한다.
 * Initializer는 컨테이너를 빈으로 등록하지 않아 컨텍스트 종료가 수명에 관여하지 않는다.
 */
public abstract class PostgresTestConfiguration {
    @ServiceConnection
    public static final PostgreSQLContainer POSTGRES =
            // 기존 테스트의 개별 컨텍스트 풀과 새 공용 컨텍스트 풀이 이관 중 함께 남는다.
            // 전체 build에서 기본 100개를 소진하므로 테스트 DB에만 200개를 허용한다.
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
