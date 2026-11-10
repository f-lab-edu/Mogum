package com.mogum.account.repository;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.mogum.support.PostgresTestConfiguration.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountSchemaMigrationTest {
    @Test
    @DisplayName("V9는 옛 상태 셋의 신원과 시각을 보존하고 이름과 크레딧 및 채움 기록을 이전한다")
    void migratesLegacyAccountsAndCredits() {
        // given
        try (var database = new MigrationDatabase()) {
            var jdbc = database.jdbc;
            for (String status : new String[]{"ACTIVE", "PENDING", "WAITLISTED"}) {
                database.insertLegacyUser(status);
            }
            var before = jdbc.queryForList("SELECT id, provider, subject, email, created_at FROM users ORDER BY id");
            var earliest = jdbc.queryForObject("SELECT now()", Timestamp.class).toInstant();

            // when
            database.flyway(null).migrate();

            // then
            var latest = jdbc.queryForObject("SELECT now()", Timestamp.class).toInstant();
            assertThat(jdbc.queryForList("SELECT id, provider, subject, email, created_at FROM users ORDER BY id"))
                    .isEqualTo(before);
            assertThat(jdbc.queryForList("SELECT status FROM users ORDER BY id", String.class))
                    .containsExactly("APPROVED", "APPLIED", "APPLIED");
            assertThat(jdbc.queryForList("SELECT name FROM users ORDER BY id", String.class))
                    .containsExactly("active", "pending", "waitlisted");
            assertThat(jdbc.queryForList("SELECT credit_balance FROM users ORDER BY id", Integer.class))
                    .containsExactly(100, 100, 100);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM users u JOIN credit_entries c ON c.user_id = u.id
                    WHERE c.type = 'REFILL' AND c.amount = 100 AND c.created_at = c.updated_at
                        AND c.created_at IS NOT NULL
                    """, Integer.class)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_entries", Integer.class)).isEqualTo(3);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM users u WHERE credit_balance <>
                        (SELECT sum(amount) FROM credit_entries WHERE user_id = u.id)
                    """, Integer.class)).isZero();
            var approved = jdbc.queryForObject("SELECT approved_at FROM users WHERE status = 'APPROVED'", Timestamp.class);
            assertThat(approved.toInstant()).isBetween(earliest, latest);
            assertThat(jdbc.queryForObject("""
                    SELECT credit_refill_at =
                        (((approved_at AT TIME ZONE 'Asia/Seoul' - interval '4 hours')::date + 30
                            + time '04:00') AT TIME ZONE 'Asia/Seoul')
                    FROM users WHERE status = 'APPROVED'
                    """, Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM users WHERE status = 'APPLIED'
                        AND approved_at IS NULL AND credit_refill_at IS NULL
                    """, Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.columns WHERE table_schema = ? AND
                        ((table_name = 'users' AND column_name IN ('role', 'terms_agreed_at', 'terms_version'))
                        OR (table_name = 'credit_entries' AND column_name = 'job_id'))
                    """, Integer.class, database.schema)).isZero();
        }
    }

    @Test
    @DisplayName("알 수 없는 옛 상태는 명시적 예외로 이전을 멈추고 기존 행과 스키마를 보존한다")
    void rejectsUnknownLegacyStatusWithoutPartialMigration() {
        // given
        try (var database = new MigrationDatabase()) {
            database.jdbc.execute("ALTER TABLE users DROP CONSTRAINT users_status_check");
            database.insertLegacyUser("UNKNOWN");
            var before = database.jdbc.queryForList("SELECT * FROM users");
            var flyway = database.flyway(null);

            // when & then
            assertThatThrownBy(flyway::migrate).isInstanceOf(FlywayException.class)
                    .hasStackTraceContaining("Unsupported legacy user status");
            assertThat(database.jdbc.queryForList("SELECT * FROM users")).isEqualTo(before);
            assertThat(flyway.info().current().getVersion().toString()).isEqualTo("8");
        }
    }

    @ParameterizedTest(name = "승인 시각 {0} → 다음 채움 {1}")
    @CsvSource({"2026-11-08T03:59:00+09:00, 2026-12-07T04:00:00+09:00",
            "2026-11-08T04:00:00+09:00, 2026-12-08T04:00:00+09:00"})
    @DisplayName("V9의 실제 채움 식은 서울 새벽 4시 양쪽에서 승인 날짜를 하루 다르게 센다")
    void calculatesRefillAcrossSeoulDayBoundary(String approval, String expected) throws IOException {
        // given
        try (var database = new MigrationDatabase();
             var resource = getClass().getResourceAsStream("/db/migration/V9__migrate_account_schema.sql")) {
            assertThat(resource).isNotNull();
            var sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            var matcher = Pattern.compile("credit_refill_at = CASE WHEN status = 'ACTIVE' THEN\\s*(.*?)\\s*ELSE NULL END",
                    Pattern.DOTALL).matcher(sql);
            assertThat(matcher.find()).isTrue();
            // 제품 식을 그대로 실행한다. now()만 고정 입력으로 바꾼다.
            var expression = matcher.group(1).replace("now()", "CAST(? AS timestamptz)");

            // when
            var actual = database.jdbc.queryForObject("SELECT " + expression, Timestamp.class, approval);

            // then
            assertThat(actual.toInstant()).isEqualTo(Instant.parse(expected));
        }
    }

    private static final class MigrationDatabase implements AutoCloseable {
        private final JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        private final String schema = "account_migration_" + UUID.randomUUID().toString().replace("-", "");
        private final String url = POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema=" + schema + ",public";
        private final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                url, POSTGRES.getUsername(), POSTGRES.getPassword()));

        private MigrationDatabase() {
            admin.execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");
            admin.execute("CREATE SCHEMA " + schema);
            flyway("8").migrate();
        }

        private void insertLegacyUser(String status) {
            jdbc.update("""
                    INSERT INTO users (provider, subject, email, status, role, created_at, updated_at)
                    VALUES ('google', ?, ?, ?, 'USER', '2026-10-01T00:00:00Z', '2026-10-01T00:00:00Z')
                    """, status, status.toLowerCase(java.util.Locale.ROOT) + "@example.com", status);
        }

        private Flyway flyway(String target) {
            var configuration = Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                    .defaultSchema(schema).schemas(schema);
            if (target != null) configuration.target(target);
            return configuration.load();
        }

        @Override
        public void close() {
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
}
