package com.mogum;

import com.mogum.support.PostgresTestConfiguration;
import com.mogum.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.springframework.context.ApplicationContext;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestContextManager;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class DatabaseSetupIntegrationTest {
    @Autowired private ApplicationContext context;
    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private Flyway flyway;

    @Autowired
    private Environment environment;

    @Test
    @DisplayName("애플리케이션이 Testcontainers Postgres와 JPA로 시작한다")
    void startsJpaWithSharedPostgres() throws SQLException {
        // when
        boolean open = entityManagerFactory.isOpen();
        // then
        assertThat(open).isTrue();
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(PostgresTestConfiguration.POSTGRES.getJdbcUrl());
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(17);
        }
    }

    @Test
    @DisplayName("Flyway V1이 적용되고 vector 확장이 설치된다")
    void appliesVectorMigration() {
        // when
        var applied = Arrays.stream(flyway.info().applied())
                .anyMatch(migration -> migration.getVersion() != null
                        && migration.getVersion().toString().equals("1")
                        && migration.getScript().equals("V1__enable_pgvector.sql"));
        // then
        assertThat(applied).isTrue();
        try (var entityManager = entityManagerFactory.createEntityManager()) {
            assertThat(((Number) entityManager.createNativeQuery(
                    "SELECT count(*) FROM pg_extension WHERE extname = 'vector'")
                    .getSingleResult()).longValue()).isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("Open in view를 끄고 스키마는 검증만 한다")
    void disablesOpenInViewAndValidatesSchema() {
        // when
        var openInView = environment.getProperty("spring.jpa.open-in-view", Boolean.class);
        // then
        assertThat(openInView).isFalse();
        assertThat(environment.getProperty("spring.jpa.open-in-view", Boolean.class)).isEqualTo(Boolean.FALSE);
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(entityManagerFactory.getProperties().get("hibernate.hbm2ddl.auto")).isEqualTo("validate");
    }

    @Test
    @DisplayName("서로 다른 컨텍스트가 DB를 공유하고 종료해도 공유 DB는 살아있다")
    void sharesContainerWithRepositoryContextAfterItCloses() throws Exception {
        // given
        var otherContext = new TestContextManager(Class.forName("com.mogum.account.repository.UserRepositoryTest")).getTestContext();
        try {
            // when
            var other = otherContext.getApplicationContext();
            // then
            assertThat(other).isNotSameAs(context);
            assertThat(other.getBeanNamesForType(com.mogum.support.IntegrationTestConfiguration.ValidationController.class)).isEmpty();
            assertThat(other.getBeanNamesForType(com.mogum.support.IntegrationTestConfiguration.FailureController.class)).isEmpty();
            var otherDataSource = other.getBean(DataSource.class);
            try (var connection = otherDataSource.getConnection()) {
                assertThat(connection.getMetaData().getURL()).isEqualTo(PostgresTestConfiguration.POSTGRES.getJdbcUrl());
            }
        } finally {
            otherContext.markApplicationContextDirty(null);
        }
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.isClosed()).isFalse();
            assertThat(connection.isValid(2)).isTrue();
        }
    }

    @Test
    @DisplayName("이관한 통합 테스트는 공용 컨텍스트 하나와 로컬 쿠키 묶음 하나만 사용한다")
    void reusesCommonContextExceptForNamedLocalCookieGroup() throws Exception {
        // given
        var names = java.util.List.of(
                "auth.SecurityConfigurationIntegrationTest", "auth.SecurityConfigurationErrorIntegrationTest",
                "auth.SecurityConfigurationLogoutIntegrationTest", "auth.SecurityConfigurationCrossOriginIntegrationTest",
                "auth.SecurityConfigurationHealthIntegrationTest", "auth.OAuthLoginHandlerIntegrationTest",
                "auth.OAuthLoginHandlerFailureIntegrationTest", "auth.OAuthLoginHandlerPkceIntegrationTest",
                "common.api.RequestValidationIntegrationTest", "common.logging.RequestLogFilterIntegrationTest",
                "support.TestRequestsIntegrationTest");
        // when
        var contexts = new java.util.ArrayList<org.springframework.context.ApplicationContext>();
        for (String name : names) contexts.add(new TestContextManager(Class.forName("com.mogum." + name))
                .getTestContext().getApplicationContext());
        // then
        assertThat(contexts).allSatisfy(actual -> assertThat(actual).isSameAs(context));
        assertThat(context.getBeanNamesForType(com.mogum.support.IntegrationTestConfiguration.CookieUnitSupport.SessionCookieConfiguration.class)).isEmpty();
        var local = new TestContextManager(Class.forName("com.mogum.auth.SecurityConfigurationLocalCookieIntegrationTest"))
                .getTestContext().getApplicationContext();
        assertThat(local).isNotSameAs(context);
        assertThat(local.getEnvironment().getProperty("app.web.cookie-domain")).isEmpty();
        assertThat(context.getEnvironment().getProperty("app.web.cookie-domain")).isEqualTo("mogum.example");
    }

}
