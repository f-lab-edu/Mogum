package com.mogum;

import com.mogum.support.PostgresTestConfiguration;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestContextManager;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class DatabaseSetupIT extends PostgresTestConfiguration {
    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private Flyway flyway;

    @Autowired
    private Environment environment;

    @Test
    void 애플리케이션이_Testcontainers_Postgres와_JPA로_시작한다() throws SQLException {
        assertTrue(entityManagerFactory.isOpen());
        try (var connection = dataSource.getConnection()) {
            assertEquals(POSTGRES.getJdbcUrl(), connection.getMetaData().getURL());
            assertEquals("PostgreSQL", connection.getMetaData().getDatabaseProductName());
            assertEquals(17, connection.getMetaData().getDatabaseMajorVersion());
        }
    }

    @Test
    void Flyway_V1이_적용되고_vector_확장이_설치된다() {
        assertTrue(Arrays.stream(flyway.info().applied())
                .anyMatch(migration -> migration.getVersion() != null
                        && migration.getVersion().toString().equals("1")
                        && migration.getScript().equals("V1__enable_pgvector.sql")));
        try (var entityManager = entityManagerFactory.createEntityManager()) {
            assertEquals(1L, ((Number) entityManager.createNativeQuery(
                    "SELECT count(*) FROM pg_extension WHERE extname = 'vector'")
                    .getSingleResult()).longValue());
        }
    }

    @Test
    void Open_in_view를_끄고_스키마는_검증만_한다() {
        assertEquals(Boolean.FALSE, environment.getProperty("spring.jpa.open-in-view", Boolean.class));
        assertEquals("validate", environment.getProperty("spring.jpa.hibernate.ddl-auto"));
        assertEquals("validate", entityManagerFactory.getProperties().get("hibernate.hbm2ddl.auto"));
    }

    @Test
    void 서로_다른_컨텍스트가_DB를_공유하고_종료해도_공유_DB는_살아있다() throws SQLException {
        var otherContext = new TestContextManager(OtherDatabaseContext.class).getTestContext();
        try {
            var otherDataSource = otherContext.getApplicationContext().getBean(DataSource.class);
            try (var connection = otherDataSource.getConnection()) {
                assertEquals(POSTGRES.getJdbcUrl(), connection.getMetaData().getURL());
            }
        } finally {
            otherContext.markApplicationContextDirty(null);
        }
        try (var connection = dataSource.getConnection()) {
            assertFalse(connection.isClosed());
            assertTrue(connection.isValid(2));
        }
    }

    @SpringBootTest(properties = "mogum.test.context=other")
    static class OtherDatabaseContext extends PostgresTestConfiguration {
    }
}
