package com.mogum.account.repository;

import com.mogum.MogumApplication;
import com.mogum.account.service.ExternalAccount;
import com.mogum.account.service.LoginResult;
import com.mogum.account.service.LoginService;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.mogum.support.PostgresTestConfiguration.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spring 테스트 컨텍스트 없이 공유 DB의 독립 스키마를 만들고 매번 삭제한다. */
class LoginIdentityMigrationTest {
    @ParameterizedTest(name = "이전 스키마 V{0}")
    @ValueSource(strings = {"3", "4", "5"})
    @DisplayName("기존 회원과 세션을 보존하며 식별자를 제공자와 subject로 이전한다")
    void migratesIdentitiesWithoutChangingUsersAndSessions(String version) throws Exception {
        // given
        try (var database = new MigrationDatabase()) {
            var legacy = database.prepareUsersAndSession(version);
            var jdbc = database.jdbc;

            // when
            database.migrateTo("8");
            // V7의 초기 Auditing 값은 V9가 실제 갱신하기 전에 확인한다.
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE updated_at = created_at", Integer.class))
                    .isEqualTo(2);
            database.flyway().migrate();

            // then
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.tables
                    WHERE table_schema = ? AND table_name = 'user_identities'
                    """, Integer.class, database.schema)).isZero();
            assertThat(database.users()).isEqualTo(legacy.users());
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema = ? AND table_name = 'users' AND column_name = 'updated_at'
                        AND (is_nullable <> 'NO' OR column_default IS NOT NULL)
                    """, Integer.class, database.schema)).isZero();
            assertThat(jdbc.queryForList("SELECT subject FROM users ORDER BY id", String.class))
                    .containsExactly("Original-Sub", "other-sub");
            assertThat(jdbc.queryForList("SELECT provider FROM users ORDER BY id", String.class))
                    .containsExactly("google", "google");
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema = ? AND table_name = 'users' AND column_name = 'google_sub'
                    """, Integer.class, database.schema)).isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema = ? AND table_name = 'users' AND column_name = 'provider'
                        AND column_default IS NOT NULL
                    """, Integer.class, database.schema)).isZero();
            assertThat(jdbc.queryForList("SELECT * FROM spring_session")).isEqualTo(legacy.sessions());
            assertThat(jdbc.queryForObject("SELECT attribute_bytes FROM spring_session_attributes", byte[].class))
                    .containsExactly(Base64.getDecoder().decode(legacy.attributeBase64()));
        }
    }

    @ParameterizedTest(name = "이전 스키마 V{0}")
    @ValueSource(strings = {"3", "4", "5"})
    @DisplayName("이전된 기존 ID 세션으로 실제 서버의 내 정보를 조회한다")
    void authenticatesMigratedSessionOnRealServer(String version) throws Exception {
        // given
        try (var database = new MigrationDatabase()) {
            var legacy = database.prepareUsersAndSession(version);
            database.flyway().migrate();
            // 마이그레이션 전용 스키마에 실제 앱을 연결한다. 공용 통합 컨텍스트를 만들지 않는다.
            try (var app = database.startApplication()) {
                int port = app.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
                String cookie = Base64.getEncoder().encodeToString(legacy.sessionId().getBytes(StandardCharsets.UTF_8));
                var client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();

                // when
                var response = client.get().uri("/api/me").cookie("SESSION", cookie)
                        .exchange().returnResult(String.class);

                // then
                assertThat(response.getStatus().value()).isEqualTo(200);
                assertThat(response.getResponseBody()).contains("old@example.com");
            }
        }
    }

    @ParameterizedTest(name = "이전 스키마 V{0}")
    @ValueSource(strings = {"3", "4", "5"})
    @DisplayName("이전된 회원의 재로그인은 기존 ID와 회원 정보를 보존한다")
    void preservesMigratedAccountOnRelogin(String version) throws Exception {
        // given
        try (var database = new MigrationDatabase()) {
            var legacy = database.prepareUsersAndSession(version);
            database.flyway().migrate();
            try (var app = database.startApplication()) {
                long id = ((Number) legacy.users().getFirst().get("id")).longValue();
                var login = app.getBean(LoginService.class);
                var account = new ExternalAccount("google", "Original-Sub", "new@example.com", "Google name", true);

                // when
                var result = login.login(account);

                // then
                assertThat(result).isEqualTo(new LoginResult.Success(id));
                assertThat(database.users()).isEqualTo(legacy.users());
                assertThat(database.jdbc.queryForList("SELECT subject FROM users ORDER BY id", String.class))
                        .containsExactly("Original-Sub", "other-sub");
            }
        }
    }

    @ParameterizedTest(name = "통합 불가 연결 {0}")
    @ValueSource(strings = {"missing", "multiple", "other"})
    @DisplayName("통합할 수 없는 연결은 데이터를 남긴 채 이전을 롤백한다")
    void rollsBackUnsupportedIdentityLinks(String scenario) {
        // given
        try (var database = new MigrationDatabase()) {
            database.migrateTo("4");
            var jdbc = database.jdbc;
            long id = jdbc.queryForObject("""
                    INSERT INTO users (email, status, role, created_at)
                    VALUES ('old@example.com', 'ACTIVE', 'USER', now()) RETURNING id
                    """, Long.class);
            if (!scenario.equals("missing")) {
                jdbc.update("INSERT INTO user_identities (user_id, provider, subject, created_at) VALUES (?, ?, 'original', now())",
                        id, scenario.equals("other") ? "other-provider" : "google");
            }
            if (scenario.equals("multiple")) {
                jdbc.update("INSERT INTO user_identities (user_id, provider, subject, created_at) VALUES (?, 'google', 'second', now())", id);
            }
            var users = jdbc.queryForList("SELECT * FROM users ORDER BY id");
            var identities = jdbc.queryForList("SELECT * FROM user_identities ORDER BY id");
            var flyway = database.flyway();

            // when & then
            assertThatThrownBy(flyway::migrate).isInstanceOf(FlywayException.class);
            assertThat(jdbc.queryForList("SELECT * FROM users ORDER BY id")).isEqualTo(users);
            assertThat(jdbc.queryForList("SELECT * FROM user_identities ORDER BY id")).isEqualTo(identities);
            assertThat(flyway.info().current().getVersion().toString()).isEqualTo("4");
        }
    }

    private record LegacyData(List<Map<String, Object>> users, List<Map<String, Object>> sessions,
                              String attributeBase64, String sessionId) {
    }

    private static final class MigrationDatabase implements AutoCloseable {
        private final JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        private final String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
        private final String url = POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema=" + schema + ",public";
        private final JdbcTemplate jdbc = new JdbcTemplate(
                new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()));

        private MigrationDatabase() {
            // V1의 확장은 테스트 스키마를 삭제해도 공유 DB에 남도록 public에 설치한다.
            admin.execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");
            admin.execute("CREATE SCHEMA " + schema);
        }

        private LegacyData prepareUsersAndSession(String version) throws Exception {
            migrateTo("3");
            var created = Instant.parse("2026-10-12T03:00:00Z");
            jdbc.update("""
                    INSERT INTO users (google_sub, email, status, role, terms_agreed_at, terms_version, created_at)
                    VALUES ('Original-Sub', 'old@example.com', 'WAITLISTED', 'USER', ?, 'v1', ?),
                           ('other-sub', 'old@example.com', 'ACTIVE', 'ADMIN', NULL, NULL, ?)
                    """, java.sql.Timestamp.from(created.plusSeconds(10)), java.sql.Timestamp.from(created),
                    java.sql.Timestamp.from(created));
            var users = jdbc.queryForList("SELECT id, email, created_at FROM users ORDER BY id");
            String primaryId = UUID.randomUUID().toString();
            String sessionId = UUID.randomUUID().toString();
            long now = System.currentTimeMillis();
            jdbc.update("INSERT INTO spring_session VALUES (?, ?, ?, ?, 604800, ?, ?)",
                    primaryId, sessionId, now, now, now + 604800000, users.getFirst().get("id").toString());
            var serialized = new ByteArrayOutputStream();
            try (var output = new ObjectOutputStream(serialized)) {
                output.writeObject(users.getFirst().get("id").toString());
            }
            byte[] attribute = serialized.toByteArray();
            jdbc.update("INSERT INTO spring_session_attributes VALUES (?, ?, ?)", primaryId,
                    FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, attribute);
            var sessions = jdbc.queryForList("SELECT * FROM spring_session");
            if (!version.equals("3")) {
                migrateTo(version);
            }

            return new LegacyData(users, sessions, Base64.getEncoder().encodeToString(attribute), sessionId);
        }

        private List<Map<String, Object>> users() {
            return jdbc.queryForList("SELECT id, email, created_at FROM users ORDER BY id");
        }

        private void migrateTo(String version) {
            Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                    .defaultSchema(schema).schemas(schema).target(version).load().migrate();
        }

        private Flyway flyway() {
            return Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                    .defaultSchema(schema).schemas(schema).load();
        }

        private ConfigurableApplicationContext startApplication() {
            return new SpringApplicationBuilder(MogumApplication.class).registerShutdownHook(false).run(
                    "--server.port=0", "--spring.datasource.url=" + url,
                    "--spring.datasource.username=" + POSTGRES.getUsername(),
                    "--spring.datasource.password=" + POSTGRES.getPassword(),
                    "--spring.flyway.default-schema=" + schema, "--spring.flyway.schemas=" + schema,
                    "--app.web.cookie-domain=",
                    "--debug=false", "--logging.level.org.springframework=INFO");
        }

        @Override
        public void close() {
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
}
