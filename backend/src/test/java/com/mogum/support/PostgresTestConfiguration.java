package com.mogum.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * DB 테스트는 {@code @SpringBootTest} (또는 DB 슬라이스 어노테이션)를 붙이고
 * 이 클래스를 상속한다. 상속된 {@code @ServiceConnection}이 접속 정보를 제공한다.
 * 컨테이너는 테스트 JVM에서 한 번 시작하며 서로 다른 Spring 컨텍스트도 공유한다.
 * {@code @Testcontainers}/{@code @Container}나 컨테이너 빈으로 등록하지 않는다:
 * 클래스/컨텍스트 종료 때 공유 DB를 멈추지 않고, JVM 종료 후 Ryuk이 정리한다.
 * DB 내용도 공유되므로 쓰기 테스트는 트랜잭션 롤백이나 명시적 정리로 격리한다.
 */
public abstract class PostgresTestConfiguration {
    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("pgvector/pgvector:pg17");

    static {
        POSTGRES.start();
    }
}
