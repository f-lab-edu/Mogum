package com.mogum.generation.call.repository;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.course.domain.LearningInput;
import com.mogum.course.domain.Level;
import com.mogum.course.domain.Purpose;
import com.mogum.generation.call.domain.LlmCallFixtures;
import com.mogum.generation.call.domain.LlmCallOutcome;
import com.mogum.generation.call.domain.LlmCallType;
import com.mogum.job.domain.GenerationJobFixtures;
import com.mogum.job.domain.JobType;
import com.mogum.job.repository.GenerationJobRepository;
import com.mogum.support.RepositoryTest;
import com.mogum.support.TestClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@RepositoryTest
class LlmCallRepositoryTest {
    @Autowired private LlmCallRepository llmCallRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private GenerationJobRepository generationJobRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TestClock testClock;

    @ParameterizedTest(name = "만들기 {0}")
    @EnumSource(JobType.class)
    @DisplayName("여섯 만들기 종류와 공용 시계의 시각을 저장하고 다시 읽는다")
    void reloadsEveryJobTypeAndAuditing(JobType type) {
        // given
        var saved = llmCallRepository.save(LlmCallFixtures.started(null, null, type, LlmCallType.CHAT, "test-model"));

        // when
        var actual = llmCallRepository.findById(saved.getId()).orElseThrow();

        // then
        assertThat(actual).isNotSameAs(saved);
        assertThat(actual.getId()).isPositive();
        assertThat(actual.getJobId()).isNull();
        assertThat(actual.getUserId()).isNull();
        assertThat(actual.getJobType()).isEqualTo(type);
        assertThat(actual.getCallType()).isEqualTo(LlmCallType.CHAT);
        assertThat(actual.getModel()).isEqualTo("test-model");
        assertThat(actual.getOutcome()).isNull();
        assertThat(actual.getDurationMs()).isNull();
        assertThat(actual.getCreatedAt()).isEqualTo(testClock.instant());
        assertThat(actual.getUpdatedAt()).isEqualTo(testClock.instant());
    }

    @ParameterizedTest(name = "호출 종류 {0}")
    @EnumSource(LlmCallType.class)
    @DisplayName("세 호출 종류를 저장하고 다시 읽는다")
    void reloadsEveryCallType(LlmCallType type) {
        // given
        var saved = llmCallRepository.save(LlmCallFixtures.started(null, null, JobType.MATERIAL, type, "test-model"));

        // when
        var actual = llmCallRepository.findById(saved.getId()).orElseThrow();

        // then
        assertThat(actual.getCallType()).isEqualTo(type);
    }

    @ParameterizedTest(name = "결과 {0}")
    @EnumSource(LlmCallOutcome.class)
    @DisplayName("일곱 호출 결과와 사용량 및 소수 정밀도를 저장한다")
    void reloadsEveryOutcomeAndUsage(LlmCallOutcome outcome) {
        // given
        var call = LlmCallFixtures.started(null, null, JobType.MATERIAL, LlmCallType.CHAT, "test-model");
        call.finish(outcome, 123, 456, new BigDecimal("0.00123456789123456789"), 789);
        var saved = llmCallRepository.save(call);

        // when
        var actual = llmCallRepository.findById(saved.getId()).orElseThrow();

        // then
        assertThat(actual.getOutcome()).isEqualTo(outcome);
        assertThat(actual.getInputTokens()).isEqualTo(123);
        assertThat(actual.getOutputTokens()).isEqualTo(456);
        assertThat(actual.getCost()).isEqualByComparingTo("0.00123456789123456789");
        assertThat(actual.getDurationMs()).isEqualTo(789);
    }

    @ParameterizedTest(name = "값 목록 {0}")
    @ValueSource(strings = {"job_type", "call_type", "outcome"})
    @DisplayName("세 값 목록은 계약 밖의 값을 거절한다")
    void rejectsUnknownValues(String column) {
        // given
        long id = started();

        // when & then
        String duration = column.equals("outcome") ? ", duration_ms = 10" : "";

        assertConstraint("23514", "llm_calls_" + column + "_check", () -> jdbcTemplate.update(
                "UPDATE llm_calls SET " + column + " = ?, updated_at = now()" + duration + " WHERE id = ?",
                "UNKNOWN", id));
    }

    @ParameterizedTest(name = "필수 칸 {0}")
    @ValueSource(strings = {"job_type", "call_type", "model", "created_at", "updated_at"})
    @DisplayName("필수 칸은 빈 값을 거절한다")
    void rejectsNullRequiredColumns(String column) {
        // given
        long id = started();

        // when & then
        assertConstraint("23502", column, () -> jdbcTemplate.update(
                "UPDATE llm_calls SET " + column + " = NULL WHERE id = ?", id));
    }

    @ParameterizedTest(name = "빈 모델 [{0}]")
    @ValueSource(strings = {"", " ", "\t\n"})
    @DisplayName("모델의 빈 글을 거절한다")
    void rejectsBlankModel(String model) {
        // given
        long id = started();

        // when & then
        assertConstraint("23514", "llm_calls_model_not_blank", () -> jdbcTemplate.update(
                "UPDATE llm_calls SET model = ?, updated_at = now() WHERE id = ?", model, id));
    }

    @ParameterizedTest(name = "종료 칸 {0}")
    @ValueSource(strings = {"outcome", "duration_ms"})
    @DisplayName("결과와 걸린 시간 중 하나만 채우는 것을 거절한다")
    void requiresOutcomeAndDurationTogether(String column) {
        // given
        long id = started();
        String value = column.equals("outcome") ? "'SUCCEEDED'" : "12";

        // when & then
        assertConstraint("23514", "llm_calls_duration_ms_check", () -> jdbcTemplate.update(
                "UPDATE llm_calls SET " + column + " = " + value + ", updated_at = now() WHERE id = ?", id));
    }

    @ParameterizedTest(name = "외래 키 {0}")
    @ValueSource(strings = {"job_id", "user_id"})
    @DisplayName("외래 키는 존재하지 않는 연결을 거절한다")
    void rejectsMissingForeignKeys(String column) {
        // given
        long id = started();

        // when & then
        assertConstraint("23503", "llm_calls_" + column + "_fkey", () -> jdbcTemplate.update(
                "UPDATE llm_calls SET " + column + " = -1, updated_at = now() WHERE id = ?", id));
    }

    @Test
    @DisplayName("작업을 지우면 작업 연결만 비고 만들기 종류와 사용자 연결은 남는다")
    void keepsCallAndJobTypeAfterJobDeletion() {
        // given
        long user = userRepository.save(UserFixtures.user("owner@example.com")).getId();
        var input = new LearningInput("자바", Level.BEGINNER, null, Purpose.WORK, null, null);
        long job = generationJobRepository.save(GenerationJobFixtures.newCourse(user, input)).getId();
        long id = llmCallRepository.save(LlmCallFixtures.started(
                job, user, JobType.NEW_COURSE, LlmCallType.CHAT, "test-model")).getId();

        // when
        jdbcTemplate.update("DELETE FROM generation_jobs WHERE id = ?", job);

        // then
        var actual = llmCallRepository.findById(id).orElseThrow();
        assertThat(actual.getJobId()).isNull();
        assertThat(actual.getUserId()).isEqualTo(user);
        assertThat(actual.getJobType()).isEqualTo(JobType.NEW_COURSE);
    }

    @Test
    @DisplayName("사람을 지우면 사용자 연결만 비고 호출 기록은 남는다")
    void keepsCallAfterUserDeletion() {
        // given
        long user = userRepository.save(UserFixtures.user("owner@example.com")).getId();
        long id = llmCallRepository.save(LlmCallFixtures.started(
                null, user, JobType.NEW_ROADMAP, LlmCallType.EMBEDDING, "test-model")).getId();

        // when
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", user);

        // then
        var actual = llmCallRepository.findById(id).orElseThrow();
        assertThat(actual.getUserId()).isNull();
        assertThat(actual.getJobType()).isEqualTo(JobType.NEW_ROADMAP);
        assertThat(actual.getModel()).isEqualTo("test-model");
    }

    @Test
    @DisplayName("계약의 칸과 타입 및 빈 값 규칙만 두고 월 합계와 작업 조회 색인을 둔다")
    void preservesColumnsAndIndexes() {
        // when
        var columns = jdbcTemplate.queryForList("""
                SELECT column_name || ':' || data_type || ':' || is_nullable
                FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'llm_calls'
                """, String.class);

        // then
        assertThat(columns).containsExactlyInAnyOrder("id:bigint:NO", "job_id:bigint:YES", "user_id:bigint:YES",
                "job_type:text:NO", "call_type:text:NO", "model:text:NO", "input_tokens:integer:YES",
                "output_tokens:integer:YES", "cost:numeric:YES", "duration_ms:integer:YES", "outcome:text:YES",
                "created_at:timestamp with time zone:NO", "updated_at:timestamp with time zone:NO");
        assertThat(jdbcTemplate.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'llm_calls'",
                String.class)).anyMatch(index -> index.contains("llm_calls_created_at_idx")
                        && index.contains("(created_at)"))
                .anyMatch(index -> index.contains("llm_calls_job_id_idx") && index.contains("(job_id)"));
    }

    private long started() {
        return llmCallRepository.save(LlmCallFixtures.started(
                null, null, JobType.NEW_COURSE, LlmCallType.CHAT, "test-model")).getId();
    }

    private static void assertConstraint(String state, String constraint, Runnable operation) {
        assertThatThrownBy(operation::run).rootCause().isInstanceOfSatisfying(SQLException.class, sql -> {
            assertThat(sql.getSQLState()).isEqualTo(state);
            assertThat(sql.getMessage()).contains(constraint);
        });
    }
}
