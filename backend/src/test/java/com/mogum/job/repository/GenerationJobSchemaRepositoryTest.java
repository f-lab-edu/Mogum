package com.mogum.job.repository;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.course.domain.*;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.course.repository.RoadmapRepository;
import com.mogum.job.domain.*;
import com.mogum.support.RepositoryTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@RepositoryTest
class GenerationJobSchemaRepositoryTest {
    @Autowired private GenerationJobRepository jobs;
    @Autowired private UserRepository users;
    @Autowired private RoadmapRepository roadmaps;
    @Autowired private CourseRepository courses;
    @Autowired private LessonRepository lessons;
    @Autowired private JdbcTemplate jdbc;

    @ParameterizedTest(name = "종류 {0}, 상태 {1}, 대상 칸 {2}")
    @MethodSource("targetCombinations")
    @DisplayName("종류와 상태마다 정해진 대상 칸만 차고 새로 배우기는 성공할 때만 결과를 갖는다")
    void enforcesTargetByTypeAndStatus(JobType type, JobStatus status, int target, boolean valid) {
        // given
        var context = context();
        long id = job(type, context);
        Runnable change = () -> jdbc.update("""
                UPDATE generation_jobs SET status = ?, course_id = ?, roadmap_id = ?, lesson_id = ?,
                lease_until = CASE WHEN ? = 'RUNNING' THEN now() ELSE NULL END,
                error_type = CASE WHEN ? = 'FAILED' THEN 'INVALID_OUTPUT' ELSE NULL END,
                updated_at = now() WHERE id = ?
                """, status.name(), target == 1 ? context.course() : null, target == 2 ? context.roadmap() : null,
                target == 3 ? context.lesson() : null, status.name(), status.name(), id);

        // when & then
        if (valid) {
            change.run();
            assertThat(jdbc.queryForObject("SELECT status FROM generation_jobs WHERE id = ?", String.class, id))
                    .isEqualTo(status.name());
        } else {
            assertConstraint("23514", "generation_jobs_target_type_check", change);
        }
    }

    @ParameterizedTest(name = "코스 {0}, 로드맵 {1}, 강의 {2}")
    @CsvSource({"true,true,false", "true,false,true", "false,true,true", "true,true,true"})
    @DisplayName("대상 칸이 둘 이상 차면 거절한다")
    void rejectsMultipleTargets(boolean course, boolean roadmap, boolean lesson) {
        // given
        var context = context();
        long id = job(JobType.MATERIAL, context);

        // when & then
        assertConstraint("23514", "generation_jobs_target_count_check", () -> jdbc.update("""
                UPDATE generation_jobs SET course_id = ?, roadmap_id = ?, lesson_id = ?, updated_at = now() WHERE id = ?
                """, course ? context.course() : null, roadmap ? context.roadmap() : null,
                lesson ? context.lesson() : null, id));
    }

    @ParameterizedTest(name = "종류 {0}, 빠진 입력 {1}")
    @MethodSource("missingNewInput")
    @DisplayName("새로 배우기의 주제와 수준 및 목적은 빠질 수 없다")
    void rejectsMissingNewInput(JobType type, String column) {
        // given
        long id = job(type, context());

        // when & then
        assertConstraint("23514", "generation_jobs_input_check", () -> update(id, column, "NULL"));
    }

    @ParameterizedTest(name = "종류 {0}, 잘못 넣은 입력 {1}")
    @MethodSource("unexpectedInput")
    @DisplayName("새로 배우기 밖에는 입력 칸 묶음을 담을 수 없다")
    void rejectsInputOutsideNewJobs(JobType type, String column, String expression) {
        // given
        long id = job(type, context());

        // when & then
        assertConstraint("23514", "generation_jobs_input_check", () -> update(id, column, expression));
    }

    @ParameterizedTest(name = "종류 {0}")
    @EnumSource(value = JobType.class, names = {"NEW_COURSE", "NEW_ROADMAP", "PLAN_ROADMAP_COURSE", "MATERIAL"})
    @DisplayName("다시 짜기 밖에는 피드백을 담을 수 없다")
    void rejectsFeedbackOutsideReplan(JobType type) {
        // given
        long id = job(type, context());

        // when & then
        assertConstraint("23514", "generation_jobs_feedback_check", () -> update(id, "feedback", "'피드백'"));
    }

    @ParameterizedTest(name = "{0} 선택 OTHER {1}, 기타 글 있음 {2}")
    @CsvSource({"level,true,false", "level,false,true", "purpose,true,false", "purpose,false,true"})
    @DisplayName("기타 선택과 기타 글의 유무가 어긋나면 거절한다")
    void rejectsInconsistentOtherInput(String column, boolean other, boolean text) {
        // given
        long id = job(JobType.NEW_COURSE, context());

        // when & then
        assertConstraint("23514", "generation_jobs_" + column + "_other_check", () -> jdbc.update(
                "UPDATE generation_jobs SET " + column + " = ?, " + column + "_other = ?, updated_at = now() WHERE id = ?",
                other ? "OTHER" : column.equals("level") ? "BEGINNER" : "WORK", text ? "기타" : null, id));
    }

    @ParameterizedTest(name = "상태 {0}, 시한 있음 {1}")
    @CsvSource({"QUEUED,true", "RUNNING,false", "SUCCEEDED,true", "FAILED,true"})
    @DisplayName("시한은 만드는 중에만 반드시 있어야 한다")
    void rejectsInconsistentLease(JobStatus status, boolean present) {
        // given
        var context = context();
        long id = job(JobType.REPLAN_COURSE, context);
        setState(id, status, context);

        // when & then
        assertConstraint("23514", "generation_jobs_lease_until_check",
                () -> update(id, "lease_until", present ? "now()" : "NULL"));
    }

    @ParameterizedTest(name = "상태 {0}, 오류 있음 {1}")
    @CsvSource({"QUEUED,true", "RUNNING,true", "SUCCEEDED,true", "FAILED,false"})
    @DisplayName("실패한 까닭은 실패일 때만 반드시 있어야 한다")
    void rejectsInconsistentError(JobStatus status, boolean present) {
        // given
        var context = context();
        long id = job(JobType.REPLAN_COURSE, context);
        setState(id, status, context);

        // when & then
        assertConstraint("23514", "generation_jobs_error_type_check",
                () -> update(id, "error_type", present ? "'INVALID_OUTPUT'" : "NULL"));
    }

    @Test
    @DisplayName("꺼낸 횟수는 음수일 수 없다")
    void rejectsNegativeClaimCount() {
        // given
        long id = job(JobType.NEW_COURSE, context());

        // when & then
        assertConstraint("23514", "generation_jobs_claim_count_check", () -> update(id, "claim_count", "-1"));
    }

    @ParameterizedTest(name = "필수 칸 {0}")
    @ValueSource(strings = {"user_id", "type", "status", "claim_count", "created_at", "updated_at"})
    @DisplayName("필수 칸은 빈 값을 거절한다")
    void rejectsNullRequiredColumns(String column) {
        // given
        long id = job(JobType.NEW_COURSE, context());

        // when & then
        assertConstraint("23502", column, () -> jdbc.update("UPDATE generation_jobs SET " + column + " = NULL WHERE id = ?", id));
    }

    @Test
    @DisplayName("꺼낸 횟수는 생략하면 0이며 공통 시각 둘은 DB 기본값이 없다")
    void preservesClaimDefaultAndAuditingColumns() {
        // given
        var context = context();

        // when
        long id = jdbc.queryForObject("""
                INSERT INTO generation_jobs (user_id, type, status, topic, level, purpose, created_at, updated_at)
                VALUES (?, 'NEW_COURSE', 'QUEUED', '주제', 'BEGINNER', 'WORK', now(), now()) RETURNING id
                """, Long.class, context.user());

        // then
        assertThat(jdbc.queryForObject("SELECT claim_count FROM generation_jobs WHERE id = ?", Integer.class, id)).isZero();
        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns WHERE table_schema = 'public'
                AND table_name = 'generation_jobs' AND column_name IN ('created_at', 'updated_at')
                AND data_type = 'timestamp with time zone' AND is_nullable = 'NO' AND column_default IS NULL
                ORDER BY column_name
                """, String.class)).containsExactly("created_at", "updated_at");
    }

    @ParameterizedTest(name = "{0} 상한 {1}, 초과 {2}")
    @MethodSource("lengthBoundaries")
    @DisplayName("사용자 글은 상한까지 저장하고 한 글자 넘으면 제약 이름과 함께 거절한다")
    void enforcesTextLength(String column, int limit, boolean exceeds) {
        // given
        long id = textJob(column);
        String text = "가".repeat(limit + (exceeds ? 1 : 0));
        Runnable change = () -> jdbc.update("UPDATE generation_jobs SET " + column + " = ?, updated_at = now() WHERE id = ?", text, id);

        // when & then
        if (exceeds) {
            assertConstraint("23514", "generation_jobs_" + column + (column.equals("feedback") ? "_length_check" : "_check"), change);
        } else {
            change.run();
            assertThat(jdbc.queryForObject("SELECT " + column + " FROM generation_jobs WHERE id = ?", String.class, id)).isEqualTo(text);
        }
    }

    @ParameterizedTest(name = "{0} 빈 글 [{1}]")
    @MethodSource("blankText")
    @DisplayName("글 칸에 빈 글과 공백뿐인 글을 넣으면 거절한다")
    void rejectsBlankText(String column, String blank) {
        // given
        long id = textJob(column);

        // when & then
        assertConstraint("23514", "generation_jobs_" + column + "_not_blank", () -> jdbc.update(
                "UPDATE generation_jobs SET " + column + " = ?, updated_at = now() WHERE id = ?", blank, id));
    }

    @ParameterizedTest(name = "값 목록 {0}")
    @ValueSource(strings = {"type", "status", "level", "purpose", "error_type"})
    @DisplayName("정해진 값 목록 밖의 글을 거절한다")
    void rejectsUnknownEnumValues(String column) {
        // given
        var context = context();
        long id = job(column.equals("type") ? JobType.MATERIAL : JobType.NEW_COURSE, context);
        if (column.equals("error_type")) setState(id, JobStatus.FAILED, context);

        // when & then
        assertConstraint("23514", "generation_jobs_" + column + (column.equals("error_type") ? "_values_check" : "_check"),
                () -> update(id, column, "'UNKNOWN'"));
    }

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource({"level,BEGINNER", "level,BASICS", "level,FAMILIAR", "level,OTHER",
            "purpose,WORK", "purpose,INTERVIEW", "purpose,GENERAL", "purpose,OTHER"})
    @DisplayName("수준과 목적의 모든 계약 값을 저장한다")
    void storesSupportedInputValues(String column, String value) {
        // given
        long id = job(JobType.NEW_ROADMAP, context());

        // when
        jdbc.update("UPDATE generation_jobs SET " + column + " = ?, " + column + "_other = ?, updated_at = now() WHERE id = ?",
                value, value.equals("OTHER") ? "기타" : null, id);

        // then
        assertThat(jdbc.queryForObject("SELECT " + column + " FROM generation_jobs WHERE id = ?", String.class, id)).isEqualTo(value);
    }

    @ParameterizedTest(name = "실패한 까닭 {0}")
    @EnumSource(JobErrorType.class)
    @DisplayName("실패한 까닭의 모든 계약 값을 저장하고 다시 읽는다")
    void storesEveryErrorType(JobErrorType type) {
        // given
        long id = job(JobType.NEW_COURSE, context());

        // when
        GenerationJobFixtures.failed(jdbc, id, type, Instant.parse("2026-11-15T00:00:00Z"));

        // then
        assertThat(jobs.findById(id).orElseThrow().getErrorType()).isEqualTo(type);
    }

    @ParameterizedTest(name = "종류 {0}, 앞 작업 상태 {1}")
    @MethodSource("duplicateTargets")
    @DisplayName("강의 자료와 로드맵 코스는 늘 하나이며 다시 짜기는 성공하지 않은 것 하나만 둔다")
    void rejectsDuplicateTarget(JobType type, JobStatus status) {
        // given
        var context = context();
        long id = job(type, context);
        setState(id, status, context);

        // when & then
        assertConstraint("23505", duplicateIndex(type), () -> job(type, context));
    }

    @ParameterizedTest(name = "다시 짜기 {0}")
    @EnumSource(value = JobType.class, names = {"REPLAN_COURSE", "REPLAN_ROADMAP"})
    @DisplayName("성공한 다시 짜기를 남긴 채 같은 대상의 새 작업을 받는다")
    void allowsNewReplanAfterSuccess(JobType type) {
        // given
        var context = context();
        long previous = job(type, context);
        setState(previous, JobStatus.SUCCEEDED, context);

        // when
        long next = job(type, context);

        // then
        assertThat(next).isNotEqualTo(previous);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM generation_jobs", Long.class)).isEqualTo(2);
    }

    @ParameterizedTest(name = "대상이 있는 종류 {0}")
    @EnumSource(value = JobType.class, names = {"MATERIAL", "PLAN_ROADMAP_COURSE", "REPLAN_COURSE", "REPLAN_ROADMAP"})
    @DisplayName("같은 종류여도 대상이 다르면 작업을 받는다")
    void allowsDifferentTargets(JobType type) {
        // given
        var first = context();
        job(type, first);
        var roadmap = roadmaps.save(RoadmapFixtures.roadmap(first.user()));
        var course = courses.save(CourseFixtures.single(first.user()));
        var lesson = lessons.save(LessonFixtures.lesson(course, 1));
        var second = new Context(first.user(), course.getId(), roadmap.getId(), lesson.getId());

        // when
        long next = job(type, second);

        // then
        assertThat(jobs.findById(next)).isPresent();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM generation_jobs", Long.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 코스라도 다른 종류의 작업을 받을 수 있다")
    void allowsDifferentTypesForSameCourse() {
        // given
        var context = context();
        job(JobType.PLAN_ROADMAP_COURSE, context);

        // when
        long next = job(JobType.REPLAN_COURSE, context);

        // then
        assertThat(jobs.findById(next).orElseThrow().getCourseId()).isEqualTo(context.course());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM generation_jobs", Long.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("대상이 없는 새로 배우기는 같은 입력으로도 여럿 저장한다")
    void allowsMultipleNewJobs() {
        // given
        var context = context();
        job(JobType.NEW_COURSE, context);
        job(JobType.NEW_ROADMAP, context);

        // when
        long next = job(JobType.NEW_COURSE, context);

        // then
        assertThat(jobs.findById(next)).isPresent();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM generation_jobs", Long.class)).isEqualTo(3);
    }

    @Test
    @DisplayName("작업을 지우면 크레딧 기록은 남고 작업 연결만 비워진다")
    void clearsCreditEntryJobOnDeletion() {
        // given
        var context = context();
        long id = job(JobType.NEW_COURSE, context);
        long entry = jdbc.queryForObject("""
                INSERT INTO credit_entries (user_id, type, amount, job_id, created_at, updated_at)
                VALUES (?, 'CONSUME', -1, ?, now(), now()) RETURNING id
                """, Long.class, context.user(), id);

        // when
        jdbc.update("DELETE FROM generation_jobs WHERE id = ?", id);

        // then
        assertThat(jdbc.queryForObject("SELECT job_id FROM credit_entries WHERE id = ?", Long.class, entry)).isNull();
        assertThat(jdbc.queryForObject("SELECT amount FROM credit_entries WHERE id = ?", Integer.class, entry)).isEqualTo(-1);
    }

    @ParameterizedTest(name = "외래 키 {0}")
    @ValueSource(strings = {"user_id", "course_id", "roadmap_id", "lesson_id"})
    @DisplayName("사용자와 대상 칸은 존재하는 행만 가리킨다")
    void rejectsMissingForeignKeys(String column) {
        // given
        var context = context();
        long id = job(switch (column) {
            case "course_id" -> JobType.REPLAN_COURSE;
            case "roadmap_id" -> JobType.REPLAN_ROADMAP;
            case "lesson_id" -> JobType.MATERIAL;
            default -> JobType.NEW_COURSE;
        }, context);

        // when & then
        assertConstraint("23503", "generation_jobs_" + column + "_fkey", () -> update(id, column, "-1"));
    }

    @ParameterizedTest(name = "대상 테이블 {0}")
    @CsvSource({"courses,course_id,REPLAN_COURSE", "roadmaps,roadmap_id,REPLAN_ROADMAP", "lessons,lesson_id,MATERIAL"})
    @DisplayName("대상을 작업보다 먼저 지우면 외래 키가 막는다")
    void rejectsDeletingTargetBeforeJob(String table, String column, JobType type) {
        // given
        var context = context();
        job(type, context);
        if (table.equals("courses")) jdbc.update("DELETE FROM lessons WHERE id = ?", context.lesson());
        long target = switch (type) {
            case REPLAN_COURSE -> context.course();
            case REPLAN_ROADMAP -> context.roadmap();
            default -> context.lesson();
        };

        // when & then
        assertConstraint("23503", "generation_jobs_" + column + "_fkey", () -> jdbc.update("DELETE FROM " + table + " WHERE id = ?", target));
    }

    @Test
    @DisplayName("꺼내기와 사람별 조회 및 중복 방지와 크레딧 복구용 색인을 둔다")
    void preservesContractIndexes() {
        // when
        var indexes = jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'generation_jobs'", String.class);

        // then
        assertThat(indexes).containsExactlyInAnyOrder("generation_jobs_pkey", "generation_jobs_status_created_at_idx",
                "generation_jobs_user_id_status_idx", "generation_jobs_material_lesson_id_key",
                "generation_jobs_plan_roadmap_course_course_id_key", "generation_jobs_replan_course_course_id_key",
                "generation_jobs_replan_roadmap_roadmap_id_key");
        assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = 'generation_jobs_status_created_at_idx'", String.class))
                .contains("(status, created_at)");
        assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = 'generation_jobs_user_id_status_idx'", String.class))
                .contains("(user_id, status)");
        assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = 'credit_entries_job_id_idx'", String.class))
                .contains("(job_id)");
    }

    private Context context() {
        long user = users.save(UserFixtures.user("owner@example.com")).getId();
        long roadmap = roadmaps.save(RoadmapFixtures.roadmap(user)).getId();
        var course = courses.save(CourseFixtures.single(user));
        long lesson = lessons.save(LessonFixtures.lesson(course, 1)).getId();
        return new Context(user, course.getId(), roadmap, lesson);
    }

    private long job(JobType type, Context context) {
        var input = new LearningInput("주제", Level.BEGINNER, null, Purpose.WORK, null, null);
        var job = switch (type) {
            case NEW_COURSE -> GenerationJobFixtures.newCourse(context.user(), input);
            case NEW_ROADMAP -> GenerationJobFixtures.newRoadmap(context.user(), input);
            case PLAN_ROADMAP_COURSE -> GenerationJobFixtures.planRoadmapCourse(context.user(), context.course());
            case REPLAN_COURSE -> GenerationJobFixtures.replanCourse(context.user(), context.course(), null);
            case REPLAN_ROADMAP -> GenerationJobFixtures.replanRoadmap(context.user(), context.roadmap(), null);
            case MATERIAL -> GenerationJobFixtures.material(context.user(), context.lesson());
        };
        return jobs.save(job).getId();
    }

    private long textJob(String column) {
        long id = job(column.equals("feedback") ? JobType.REPLAN_COURSE : JobType.NEW_COURSE, context());
        if (column.endsWith("_other")) {
            update(id, column.substring(0, column.length() - 6), "'OTHER', " + column + " = '기타'");
        }
        return id;
    }

    private void setState(long id, JobStatus status, Context context) {
        var instant = Instant.parse("2026-11-15T00:00:00Z");
        switch (status) {
            case QUEUED -> { }
            case RUNNING -> GenerationJobFixtures.running(jdbc, id, 1, instant, instant.plusSeconds(300));
            case FAILED -> GenerationJobFixtures.failed(jdbc, id, JobErrorType.INVALID_OUTPUT, instant);
            case SUCCEEDED -> {
                var job = jobs.findById(id).orElseThrow();
                GenerationJobFixtures.succeeded(jdbc, id, job.getCourseId(), job.getRoadmapId(), job.getLessonId(), instant);
            }
        }
    }

    private void update(long id, String column, String expression) {
        jdbc.update("UPDATE generation_jobs SET " + column + " = " + expression + ", updated_at = now() WHERE id = ?", id);
    }

    private static String duplicateIndex(JobType type) {
        return switch (type) {
            case MATERIAL -> "generation_jobs_material_lesson_id_key";
            case PLAN_ROADMAP_COURSE -> "generation_jobs_plan_roadmap_course_course_id_key";
            case REPLAN_COURSE -> "generation_jobs_replan_course_course_id_key";
            case REPLAN_ROADMAP -> "generation_jobs_replan_roadmap_roadmap_id_key";
            default -> throw new IllegalArgumentException(type.name());
        };
    }

    private static void assertConstraint(String state, String constraint, Runnable operation) {
        assertThatThrownBy(operation::run).rootCause().isInstanceOfSatisfying(SQLException.class, sql -> {
            assertThat(sql.getSQLState()).isEqualTo(state);
            assertThat(sql.getMessage()).contains(constraint);
        });
    }

    static Stream<Arguments> targetCombinations() {
        return Arrays.stream(JobType.values()).flatMap(type -> Arrays.stream(JobStatus.values()).flatMap(status ->
                Stream.of(0, 1, 2, 3).map(target -> {
                    int expected = switch (type) {
                        case NEW_COURSE -> status == JobStatus.SUCCEEDED ? 1 : 0;
                        case NEW_ROADMAP -> status == JobStatus.SUCCEEDED ? 2 : 0;
                        case PLAN_ROADMAP_COURSE, REPLAN_COURSE -> 1;
                        case REPLAN_ROADMAP -> 2;
                        case MATERIAL -> 3;
                    };
                    return Arguments.of(type, status, target, target == expected);
                })));
    }

    static Stream<Arguments> missingNewInput() {
        return Stream.of(JobType.NEW_COURSE, JobType.NEW_ROADMAP).flatMap(type ->
                Stream.of("topic", "level", "purpose").map(column -> Arguments.of(type, column)));
    }

    static Stream<Arguments> unexpectedInput() {
        return Stream.of(JobType.PLAN_ROADMAP_COURSE, JobType.REPLAN_COURSE, JobType.REPLAN_ROADMAP, JobType.MATERIAL)
                .flatMap(type -> Stream.of(Arguments.of(type, "topic", "'주제'"), Arguments.of(type, "level", "'BEGINNER'"),
                        Arguments.of(type, "purpose", "'WORK'"), Arguments.of(type, "level_other", "'기타'"),
                        Arguments.of(type, "purpose_other", "'기타'"), Arguments.of(type, "memo", "'메모'")));
    }

    static Stream<Arguments> lengthBoundaries() {
        return Stream.of("topic,100", "level_other,50", "purpose_other,50", "memo,500", "feedback,500")
                .map(pair -> pair.split(",")).flatMap(pair -> Stream.of(false, true)
                        .map(exceeds -> Arguments.of(pair[0], Integer.parseInt(pair[1]), exceeds)));
    }

    static Stream<Arguments> blankText() {
        return Stream.of("topic", "level_other", "purpose_other", "memo", "feedback")
                .flatMap(column -> Stream.of("", "   ", "\t\n").map(blank -> Arguments.of(column, blank)));
    }

    static Stream<Arguments> duplicateTargets() {
        return Stream.of(JobType.MATERIAL, JobType.PLAN_ROADMAP_COURSE, JobType.REPLAN_COURSE, JobType.REPLAN_ROADMAP)
                .flatMap(type -> Arrays.stream(JobStatus.values())
                        .filter(status -> status != JobStatus.SUCCEEDED || type == JobType.MATERIAL || type == JobType.PLAN_ROADMAP_COURSE)
                        .map(status -> Arguments.of(type, status)));
    }

    private record Context(long user, long course, long roadmap, long lesson) { }
}
