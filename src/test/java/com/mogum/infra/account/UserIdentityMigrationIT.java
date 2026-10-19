package com.mogum.infra.account;

import com.mogum.MogumApplication;
import com.mogum.core.account.ExternalAccount;
import com.mogum.core.account.ExternalIdentity;
import com.mogum.core.account.Login;
import com.mogum.core.account.LoginResult;
import com.mogum.support.PostgresTestConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
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

class UserIdentityMigrationIT extends PostgresTestConfiguration {
    @Test
    void V3의_회원과_세션을_보존하며_구글_식별자를_별도_테이블로_이전한다() throws Exception {
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

            Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                    .defaultSchema(schema).schemas(schema).load().migrate();

            assertEquals(0, jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema = ? AND table_name = 'users' AND column_name = 'google_sub'
                    """, Integer.class, schema));
            assertEquals(users, jdbc.queryForList("SELECT * FROM users ORDER BY id"));
            var identities = jdbc.queryForList("SELECT user_id, provider, subject, created_at FROM user_identities ORDER BY user_id");
            assertEquals(2, identities.size());
            assertEquals(users.getFirst().get("id"), identities.getFirst().get("user_id"));
            assertEquals("google", identities.getFirst().get("provider"));
            assertEquals("Original-Sub", identities.getFirst().get("subject"));
            assertEquals(users.getFirst().get("created_at"), identities.getFirst().get("created_at"));
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
                assertEquals(users, jdbc.queryForList("SELECT * FROM users ORDER BY id"));
                assertEquals(identities, jdbc.queryForList("SELECT user_id, provider, subject, created_at FROM user_identities ORDER BY user_id"));
            }
        } finally {
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
}
