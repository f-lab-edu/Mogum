package com.mogum.deploy;

import com.mogum.account.domain.CreditEntry;
import com.mogum.account.domain.User;
import com.mogum.account.domain.UserFixtures;
import com.mogum.account.domain.UserStatus;
import com.mogum.account.repository.CreditEntryRepository;
import com.mogum.account.repository.UserRepository;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestSessions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.testcontainers.images.builder.Transferable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;

import static com.mogum.support.PostgresTestConfiguration.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class OperatorSqlIntegrationTest {
    private static final Path SQL = Path.of("../deploy/sql");
    @Autowired private UserRepository users;
    @Autowired private CreditEntryRepository entries;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JdbcIndexedSessionRepository sessionRepository;

    @Test
    @DisplayName("신청 목록은 승인된 수와 신청 및 거절된 사람의 ID 이메일 이름 상태 신청 시각을 보인다")
    void listsApplicationsAndApprovedCount() throws Exception {
        // given
        var applied = saveUser(UserStatus.APPLIED, "applied@example.com");
        var rejected = saveUser(UserStatus.REJECTED, "rejected@example.com");
        saveUser(UserStatus.APPROVED, "approved@example.com");
        saveUser(UserStatus.APPROVED, "other@example.com");

        // when
        var output = execute("list-applications.sql", 0, null);

        // then
        assertThat(output.lines().toList()).hasSize(3).first().isEqualTo("2");
        assertThat(output).contains(applied.getId() + "|applied@example.com|applied|APPLIED|",
                rejected.getId() + "|rejected@example.com|rejected|REJECTED|",
                "2026-11-08");
        assertThat(output).doesNotContain("approved@example.com", "other@example.com");
    }

    @Test
    @DisplayName("대기자가 없어도 신청 목록은 승인된 수를 보인다")
    void showsApprovedCountWithoutApplications() throws Exception {
        // given
        saveUser(UserStatus.APPROVED, "approved@example.com");

        // when
        var output = execute("list-applications.sql", 0, null);

        // then
        assertThat(output.strip()).isEqualTo("1");
    }

    @Test
    @DisplayName("승인은 시각과 다음 채움 및 수정 시각을 넣고 신원과 신청 시각 및 크레딧 기록을 보존한다")
    void approvesApplicationWithoutChangingCredits() throws Exception {
        // given
        var user = saveUser(UserStatus.APPLIED, "me@example.com");
        var other = saveUser(UserStatus.APPLIED, "other@example.com");
        var otherBefore = jdbc.queryForMap("SELECT * FROM users WHERE id = ?", other.getId());
        var before = jdbc.queryForMap("SELECT provider, subject, email, name, created_at, credit_balance FROM users WHERE id = ?",
                user.getId());
        var credits = jdbc.queryForList("SELECT * FROM credit_entries");
        var earliest = databaseNow();

        // when
        var output = execute("approve-account.sql", user.getId(), null);

        // then
        var approved = users.findById(user.getId()).orElseThrow();
        assertThat(output).contains("UPDATE 1");
        assertThat(approved.getStatus()).isEqualTo(UserStatus.APPROVED);
        assertThat(approved.getApprovedAt()).isBetween(earliest, databaseNow());
        assertThat(approved.getCreditRefillAt()).isAfter(approved.getApprovedAt());
        assertThat(approved.getUpdatedAt()).isEqualTo(approved.getApprovedAt()).isAfter(user.getUpdatedAt());
        assertThat(jdbc.queryForMap("SELECT provider, subject, email, name, created_at, credit_balance FROM users WHERE id = ?",
                user.getId())).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM credit_entries")).isEqualTo(credits);
        assertThat(jdbc.queryForMap("SELECT * FROM users WHERE id = ?", other.getId())).isEqualTo(otherBefore);
    }

    @ParameterizedTest(name = "승인 시각 {0} → 다음 채움 {1}")
    @CsvSource({"2026-11-08T03:59:00+09:00, 2026-12-07T04:00:00+09:00",
            "2026-11-08T04:00:00+09:00, 2026-12-08T04:00:00+09:00"})
    @DisplayName("승인 파일의 now만 고정하면 서울 새벽 4시 양쪽의 채움 날짜가 하루 갈린다")
    void calculatesRefillAcrossSeoulDayBoundary(String approval, String expected) throws Exception {
        // given
        var user = saveUser(UserStatus.APPLIED, "me@example.com");

        // when
        var output = execute("approve-account.sql", user.getId(), approval);

        // then
        var approved = users.findById(user.getId()).orElseThrow();
        assertThat(output).contains("UPDATE 1");
        assertThat(approved.getApprovedAt()).isEqualTo(Instant.parse(approval));
        assertThat(approved.getUpdatedAt()).isEqualTo(Instant.parse(approval));
        assertThat(approved.getCreditRefillAt()).isEqualTo(Instant.parse(expected));
    }

    @ParameterizedTest(name = "기존 상태 {0}")
    @EnumSource(value = UserStatus.class, names = {"APPROVED", "REJECTED"})
    @DisplayName("승인은 이미 승인되거나 거절된 사람의 행과 기록을 바꾸지 않는다")
    void preservesAccountsThatCannotBeApproved(UserStatus status) throws Exception {
        // given
        var user = saveUser(status, "me@example.com");
        var before = jdbc.queryForList("SELECT * FROM users");
        var credits = jdbc.queryForList("SELECT * FROM credit_entries");

        // when
        var output = execute("approve-account.sql", user.getId(), null);

        // then
        assertThat(output).contains("UPDATE 0");
        assertThat(jdbc.queryForList("SELECT * FROM users")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM credit_entries")).isEqualTo(credits);
    }

    @ParameterizedTest(name = "{0}: {1} → {2}")
    @CsvSource({"reject-account.sql, APPLIED, REJECTED", "reopen-application.sql, REJECTED, APPLIED"})
    @DisplayName("거절과 거절 풀기는 대상의 상태와 수정 시각만 바꾼다")
    void changesRejectionStatusAndUpdatedTime(String file, UserStatus initial, UserStatus expected) throws Exception {
        // given
        var user = saveUser(initial, "me@example.com");
        var other = saveUser(initial, "other@example.com");
        var otherBefore = jdbc.queryForMap("SELECT * FROM users WHERE id = ?", other.getId());
        var before = jdbc.queryForMap("SELECT provider, subject, email, name, created_at, approved_at, credit_balance, credit_refill_at FROM users WHERE id = ?",
                user.getId());
        var credits = jdbc.queryForList("SELECT * FROM credit_entries");
        var earliest = databaseNow();

        // when
        var output = execute(file, user.getId(), null);

        // then
        var changed = users.findById(user.getId()).orElseThrow();
        assertThat(output).contains("UPDATE 1");
        assertThat(changed.getStatus()).isEqualTo(expected);
        assertThat(changed.getUpdatedAt()).isBetween(earliest, databaseNow()).isAfter(user.getUpdatedAt());
        assertThat(jdbc.queryForMap("SELECT * FROM users WHERE id = ?", other.getId())).isEqualTo(otherBefore);
        assertThat(jdbc.queryForMap("SELECT provider, subject, email, name, created_at, approved_at, credit_balance, credit_refill_at FROM users WHERE id = ?",
                user.getId())).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM credit_entries")).isEqualTo(credits);
    }

    @ParameterizedTest(name = "{0}: 기존 상태 {1}")
    @CsvSource({"reject-account.sql, APPROVED", "reject-account.sql, REJECTED",
            "reopen-application.sql, APPLIED", "reopen-application.sql, APPROVED"})
    @DisplayName("거절과 거절 풀기는 조건에 맞지 않으면 아무 행도 바꾸지 않는다")
    void preservesAccountsOutsideRejectionTransition(String file, UserStatus status) throws Exception {
        // given
        var user = saveUser(status, "me@example.com");
        var before = jdbc.queryForList("SELECT * FROM users");

        // when
        var output = execute(file, user.getId(), null);

        // then
        assertThat(output).contains("UPDATE 0");
        assertThat(jdbc.queryForList("SELECT * FROM users")).isEqualTo(before);
    }

    @ParameterizedTest(name = "대상 상태 {0}")
    @EnumSource(value = UserStatus.class, names = {"APPLIED", "REJECTED"})
    @DisplayName("계정 삭제는 본인의 기록과 모든 세션 및 FK 속성을 지우고 다른 사람은 보존한다")
    void deletesOnlyRequestedAccountAndItsSessions(UserStatus status) throws Exception {
        // given
        var user = saveUser(status, "me@example.com");
        var other = saveUser(UserStatus.APPLIED, "other@example.com");
        var sessions = new TestSessions(sessionRepository);
        var first = sessions.id(sessions.create(user.getId()));
        var second = sessions.id(sessions.create(user.getId()));
        var remaining = sessions.id(sessions.create(other.getId()));
        var otherUser = jdbc.queryForMap("SELECT * FROM users WHERE id = ?", other.getId());
        var otherCredits = jdbc.queryForList("SELECT * FROM credit_entries WHERE user_id = ?", other.getId());
        var otherSessions = jdbc.queryForList("SELECT * FROM spring_session WHERE principal_name = ?", other.getId().toString());
        var attributesBefore = count("spring_session_attributes");

        // when
        var output = execute("delete-account.sql", user.getId(), null);

        // then
        assertThat(output).contains("DELETE 1", "DELETE 2", "COMMIT");
        assertThat(users.findById(user.getId())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_entries WHERE user_id = ?", Integer.class,
                user.getId())).isZero();
        assertThat(sessionRepository.findById(first)).isNull();
        assertThat(sessionRepository.findById(second)).isNull();
        assertThat(sessionRepository.findById(remaining)).isNotNull();
        assertThat(count("spring_session_attributes")).isEqualTo(attributesBefore - 2);
        assertThat(jdbc.queryForMap("SELECT * FROM users WHERE id = ?", other.getId())).isEqualTo(otherUser);
        assertThat(jdbc.queryForList("SELECT * FROM credit_entries WHERE user_id = ?", other.getId())).isEqualTo(otherCredits);
        assertThat(jdbc.queryForList("SELECT * FROM spring_session WHERE principal_name = ?", other.getId().toString()))
                .isEqualTo(otherSessions);
    }

    @Test
    @DisplayName("승인된 계정은 크레딧 기록과 세션 속성까지 아무것도 지우지 않는다")
    void preservesApprovedAccountOnDeletion() throws Exception {
        // given
        var user = saveUser(UserStatus.APPROVED, "me@example.com");
        new TestSessions(sessionRepository).create(user.getId());
        var before = jdbc.queryForList("SELECT * FROM users");
        var credits = jdbc.queryForList("SELECT * FROM credit_entries");
        var sessions = jdbc.queryForList("SELECT * FROM spring_session");
        var attributes = jdbc.queryForList("SELECT * FROM spring_session_attributes");

        // when
        var output = execute("delete-account.sql", user.getId(), null);

        // then
        assertThat(output.lines().filter(line -> line.startsWith("DELETE")).toList())
                .containsExactly("DELETE 0", "DELETE 0", "DELETE 0");
        assertThat(jdbc.queryForList("SELECT * FROM users")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM credit_entries")).isEqualTo(credits);
        assertThat(jdbc.queryForList("SELECT * FROM spring_session")).isEqualTo(sessions);
        assertThat(jdbc.queryForList("SELECT * FROM spring_session_attributes"))
                .usingRecursiveComparison().isEqualTo(attributes);
    }

    @Test
    @DisplayName("계정 삭제의 마지막 쓰기가 실패하면 앞서 지운 크레딧 기록과 세션 속성도 롤백된다")
    void rollsBackEntireDeletionOnFailure() throws Exception {
        // given
        var user = saveUser(UserStatus.APPLIED, "me@example.com");
        var sessions = new TestSessions(sessionRepository);
        var sessionId = sessions.id(sessions.create(user.getId()));
        var before = jdbc.queryForList("SELECT * FROM users");
        var credits = jdbc.queryForList("SELECT * FROM credit_entries");
        var attributes = jdbc.queryForList("SELECT * FROM spring_session_attributes");
        // 마지막 DELETE 실패를 주입한다. 앱 스키마와 컨텍스트는 시험 뒤 원상복구한다.
        jdbc.execute("""
                CREATE FUNCTION fail_operator_delete() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'operator deletion test failure'; END $$
                """);
        try {
            jdbc.execute("""
                    CREATE TRIGGER fail_operator_delete BEFORE DELETE ON users
                    FOR EACH ROW EXECUTE FUNCTION fail_operator_delete()
                    """);

            // when
            var result = run("delete-account.sql", user.getId(), null);

            // then
            assertThat(result.getExitCode()).isNotZero();
            assertThat(result.getStderr()).contains("operator deletion test failure");
            assertThat(result.getStdout()).contains("DELETE 1").doesNotContain("COMMIT");
            assertThat(jdbc.queryForList("SELECT * FROM users")).isEqualTo(before);
            assertThat(jdbc.queryForList("SELECT * FROM credit_entries")).isEqualTo(credits);
            assertThat(sessionRepository.findById(sessionId)).isNotNull();
            assertThat(jdbc.queryForList("SELECT * FROM spring_session_attributes"))
                    .usingRecursiveComparison().isEqualTo(attributes);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_operator_delete ON users");
            jdbc.execute("DROP FUNCTION fail_operator_delete()");
        }
    }

    private User saveUser(UserStatus status, String email) {
        var user = users.save(UserFixtures.withStatus(UserFixtures.user(email), status));
        entries.save(CreditEntry.refill(user, user.getCreditBalance()));
        return user;
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("SELECT now()", Timestamp.class).toInstant();
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private String execute(String file, long userId, String fixedNow) throws Exception {
        var result = run(file, userId, fixedNow);
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
        return result.getStdout();
    }

    private org.testcontainers.containers.Container.ExecResult run(String file, long userId, String fixedNow) throws Exception {
        var sql = Files.readString(SQL.resolve(file));
        // 사람과 같은 파일과 psql -v 치환을 쓴다. 경계 시험에서만 now()를 고정 시각으로 바꾼다.
        if (fixedNow != null) sql = sql.replace("now()", "TIMESTAMPTZ '" + fixedNow + "'");
        String remote = "/tmp/mogum-operator.sql";
        POSTGRES.copyFileToContainer(Transferable.of(sql.getBytes(StandardCharsets.UTF_8)), remote);
        return POSTGRES.execInContainer("psql", "-X", "-A", "-t", "-F", "|",
                "-v", "ON_ERROR_STOP=1", "-v", "user_id=" + userId,
                "-U", POSTGRES.getUsername(), "-d", POSTGRES.getDatabaseName(), "-f", remote);
    }
}
