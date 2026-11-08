package com.mogum.support;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@RepositoryTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DatabaseCleanerTest {
    @Autowired private UserRepository users;
    @Autowired private DataSource dataSource;
    @Autowired private TestClock clock;

    @Test
    @Order(1)
    @DisplayName("테스트에서 행을 저장하고 공용 시계를 옮길 수 있다")
    void commitsDataAndMovesClock() {
        // given
        users.save(UserFixtures.user("isolation@example.com"));

        // when
        clock.advance(Duration.ofDays(3));

        // then
        assertThat(new JdbcTemplate(dataSource).queryForObject("SELECT count(*) FROM users", Long.class))
                .isEqualTo(1);
        assertThat(clock.instant()).isEqualTo(TestClock.INITIAL_INSTANT.plus(Duration.ofDays(3)));
    }

    @Test
    @Order(2)
    @DisplayName("다음 테스트는 앞 테스트의 행과 시각 없이 시작하며 마이그레이션 이력은 남는다")
    void startsWithEmptyTablesAndInitialClock() {
        // given
        var jdbc = new JdbcTemplate(dataSource);

        // when
        var count = jdbc.queryForObject("SELECT count(*) FROM users", Long.class);

        // then
        assertThat(count).isZero();
        assertThat(clock.instant()).isEqualTo(TestClock.INITIAL_INSTANT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history", Long.class)).isPositive();
    }

    @Test
    @Order(3)
    @DisplayName("정리기는 카탈로그에서 새 테이블을 찾아 외래 키가 있어도 비우고 이력은 보존한다")
    void discoversNewTablesWithForeignKeys() {
        // given
        var jdbc = new JdbcTemplate(dataSource);
        var user = users.save(UserFixtures.user("catalog@example.com"));
        var history = jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history", Long.class);
        jdbc.execute("CREATE TABLE cleaner_probe (user_id bigint REFERENCES users(id))");
        try {
            jdbc.update("INSERT INTO cleaner_probe VALUES (?)", user.getId());

            // when
            new DatabaseCleaner().clean(dataSource);

            // then
            assertThat(jdbc.queryForObject("SELECT count(*) FROM cleaner_probe", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history", Long.class))
                    .isEqualTo(history);
        } finally {
            jdbc.execute("DROP TABLE cleaner_probe");
        }
    }
}
