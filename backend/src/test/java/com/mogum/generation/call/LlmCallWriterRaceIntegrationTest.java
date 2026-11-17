package com.mogum.generation.call;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.course.domain.LearningInput;
import com.mogum.course.domain.Level;
import com.mogum.course.domain.Purpose;
import com.mogum.generation.call.domain.LlmCallType;
import com.mogum.generation.call.repository.LlmCallRepository;
import com.mogum.job.domain.GenerationJobFixtures;
import com.mogum.job.domain.JobType;
import com.mogum.job.repository.GenerationJobRepository;
import com.mogum.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class LlmCallWriterRaceIntegrationTest {
    @Autowired private LlmCallWriter llmCallWriter;
    @Autowired private LlmCallRepository llmCallRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private GenerationJobRepository generationJobRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;

    @ParameterizedTest(name = "확인 직후 삭제하는 연결 {0}")
    @ValueSource(strings = {"job_id", "user_id"})
    @DisplayName("확인 직후 삭제와 경합해도 한 번 재시도해 기록하고 모델 표식을 로그에 남기지 않는다")
    void retriesAfterConcurrentDeletion(String column, CapturedOutput output) throws Exception {
        // given
        long user = userRepository.save(UserFixtures.user("owner@example.com")).getId();
        var input = new LearningInput("자바", Level.BEGINNER, null, Purpose.WORK, null, null);
        Long job = column.equals("job_id")
                ? generationJobRepository.save(GenerationJobFixtures.newCourse(user, input)).getId() : null;
        String marker = "private-call-model-marker";

        // 실제 INSERT 직전만 멈춰, 연결 존재 확인과 FK 검사 사이에 다른 커넥션이 삭제하도록 한다.
        jdbcTemplate.execute("""
                CREATE FUNCTION test_wait_llm_call() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN PERFORM pg_advisory_xact_lock(748129); RETURN NEW; END $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER test_wait_llm_call BEFORE INSERT ON llm_calls
                FOR EACH ROW EXECUTE FUNCTION test_wait_llm_call()
                """);

        try (var executor = Executors.newSingleThreadExecutor(); var barrier = dataSource.getConnection()) {
            barrier.setAutoCommit(false);
            try {
                try (var statement = barrier.createStatement()) {
                    statement.execute("SELECT pg_advisory_xact_lock(748129)");
                }
                var started = executor.submit(() -> llmCallWriter.start(
                        job, user, JobType.NEW_COURSE, LlmCallType.CHAT, marker));
                await().atMost(Duration.ofSeconds(10)).until(() -> Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE locktype = 'advisory' AND objid = 748129 AND NOT granted)",
                        Boolean.class)));

                // when
                jdbcTemplate.update("DELETE FROM " + (column.equals("job_id") ? "generation_jobs" : "users")
                        + " WHERE id = ?", column.equals("job_id") ? job : user);
                barrier.commit();

                // then
                long id = started.get(10, TimeUnit.SECONDS);
                var actual = llmCallRepository.findById(id).orElseThrow();
                assertThat(actual.getJobId()).isNull();
                assertThat(actual.getUserId()).isEqualTo(column.equals("user_id") ? null : user);
                assertThat(actual.getJobType()).isEqualTo(JobType.NEW_COURSE);
                assertThat(actual.getModel()).isEqualTo(marker);
                assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM llm_calls", Long.class)).isEqualTo(1);
                assertThat(output.getAll()).doesNotContain(marker);
            } finally {
                barrier.rollback();
            }
        } finally {
            jdbcTemplate.execute("DROP TRIGGER test_wait_llm_call ON llm_calls");
            jdbcTemplate.execute("DROP FUNCTION test_wait_llm_call()");
        }
    }
}
