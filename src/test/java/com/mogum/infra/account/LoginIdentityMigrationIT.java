package com.mogum.infra.account;

import com.mogum.MogumApplication;
import com.mogum.core.account.ExternalAccount;
import com.mogum.core.account.ExternalIdentity;
import com.mogum.core.account.Login;
import com.mogum.core.account.LoginResult;
import com.mogum.support.PostgresTestConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.session.FindByIndexNameSessionRepository;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LoginIdentityMigrationIT extends PostgresTestConfiguration {
    @ParameterizedTest
    @ValueSource(strings = {"3", "4", "5"})
    void 기존_회원과_세션을_보존하며_식별자를_provider와_subject로_이전한다(String version) throws Exception {
        var admin = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
        // V1의 확장은 테스트 스키마를 삭제해도 공유 DB에 남도록 public에 설치한다.
        admin.execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");
        admin.execute("CREATE SCHEMA " + schema);
        String url = POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema=" + schema + ",public";
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()));
        try {
            Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                    .defaultSchema(schema).schemas(schema).target("3").load().migrate();
            var created = Instant.parse("2026-10-12T03:00:00Z");
            jdbc.update("""
                    INSERT INTO users (google_sub, email, status, role, terms_agreed_at, terms_version, created_at)
                    VALUES ('Original-Sub', 'old@example.com', 'WAITLISTED', 'USER', ?, 'v1', ?),
                           ('other-sub', 'old@example.com', 'ACTIVE', 'ADMIN', NULL, NULL, ?)
                    """, java.sql.Timestamp.from(created.plusSeconds(10)), java.sql.Timestamp.from(created),
                    java.sql.Timestamp.from(created));
            var users = jdbc.queryForList("SELECT id, email, status, role, terms_agreed_at, terms_version, created_at FROM users ORDER BY id");
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
                Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                        .defaultSchema(schema).schemas(schema).target(version).load().migrate();
            }

            Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                    .defaultSchema(schema).schemas(schema).load().migrate();

            assertEquals(0, jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.tables
                    WHERE table_schema = ? AND table_name = 'user_identities'
                    """, Integer.class, schema));
            assertEquals(users, jdbc.queryForList("SELECT id, email, status, role, terms_agreed_at, terms_version, created_at FROM users ORDER BY id"));
            assertEquals(java.util.List.of("Original-Sub", "other-sub"),
                    jdbc.queryForList("SELECT subject FROM users ORDER BY id", String.class));
            assertEquals(java.util.List.of("google", "google"),
                    jdbc.queryForList("SELECT provider FROM users ORDER BY id", String.class));
            assertEquals(0, jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema = ? AND table_name = 'users' AND column_name = 'google_sub'
                    """, Integer.class, schema));
            assertEquals(0, jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema = ? AND table_name = 'users' AND column_name = 'provider'
                        AND column_default IS NOT NULL
                    """, Integer.class, schema));
            assertEquals(sessions, jdbc.queryForList("SELECT * FROM spring_session"));
            assertArrayEquals(attribute, jdbc.queryForObject("SELECT attribute_bytes FROM spring_session_attributes", byte[].class));

            // 이전된 DB에 새 서버를 연결해 기존 ID 세션과 재로그인을 실제 배선으로 확인한다.
            try (var app = new SpringApplicationBuilder(MogumApplication.class).registerShutdownHook(false).run(
                    "--server.port=0", "--spring.datasource.url=" + url,
                    "--spring.datasource.username=" + POSTGRES.getUsername(),
                    "--spring.datasource.password=" + POSTGRES.getPassword(),
                    "--spring.flyway.default-schema=" + schema, "--spring.flyway.schemas=" + schema,
                    "--app.auth.admin-emails=new@example.com", "--app.web.cookie-domain=",
                    "--debug=false", "--logging.level.org.springframework=INFO")) {
                int port = app.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
                String cookie = Base64.getEncoder().encodeToString(sessionId.getBytes(StandardCharsets.UTF_8));
                var me = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/me"))
                        .header("Cookie", "SESSION=" + cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, me.statusCode());
                assertTrue(me.body().contains("old@example.com"));
                long id = ((Number) users.getFirst().get("id")).longValue();
                assertEquals(new LoginResult.Success(id), app.getBean(Login.class).login(
                        new ExternalAccount(new ExternalIdentity("google", "Original-Sub"), "new@example.com", true)));
                assertEquals(users, jdbc.queryForList("SELECT id, email, status, role, terms_agreed_at, terms_version, created_at FROM users ORDER BY id"));
                assertEquals(java.util.List.of("Original-Sub", "other-sub"),
                        jdbc.queryForList("SELECT subject FROM users ORDER BY id", String.class));
            }
        } finally {
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "multiple", "other"})
    void 통합할_수_없는_연결은_데이터를_남긴_채_이전을_롤백한다(String scenario) {
        var admin = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");
        admin.execute("CREATE SCHEMA " + schema);
        String url = POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema=" + schema + ",public";
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()));
        try {
            Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                    .defaultSchema(schema).schemas(schema).target("4").load().migrate();
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
            var flyway = Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                    .defaultSchema(schema).schemas(schema).load();
            assertThrows(org.flywaydb.core.api.FlywayException.class, flyway::migrate);
            assertEquals(users, jdbc.queryForList("SELECT * FROM users ORDER BY id"));
            assertEquals(identities, jdbc.queryForList("SELECT * FROM user_identities ORDER BY id"));
            assertEquals("4", flyway.info().current().getVersion().toString());
        } finally {
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

}
