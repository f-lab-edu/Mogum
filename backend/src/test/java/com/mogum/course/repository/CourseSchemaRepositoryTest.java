package com.mogum.course.repository;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.support.RepositoryTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@RepositoryTest
class CourseSchemaRepositoryTest {
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("단일 코스와 로드맵의 코스는 각각 입력과 계획을 담는다")
    void storesSingleAndRoadmapCourses() {
        // given
        long user = owner();
        long roadmap = roadmap(user);
        long single = single(user);

        // when
        long planned = planned(user, roadmap, 1);

        // then
        assertThat(jdbc.queryForMap("SELECT roadmap_id, position, plan_description, plan_concepts FROM courses WHERE id = ?", single))
                .allSatisfy((column, value) -> assertThat(value).isNull());
        assertThat(jdbc.queryForMap("SELECT topic, level, level_other, purpose, purpose_other, memo FROM courses WHERE id = ?", planned))
                .allSatisfy((column, value) -> assertThat(value).isNull());
        assertThat(jdbc.queryForObject("SELECT status FROM courses WHERE id = ?", String.class, planned)).isEqualTo("NOT_PLANNED");
    }

    @ParameterizedTest(name = "단일 코스의 잘못된 칸: {0}")
    @CsvSource(value = {"position,1", "plan_description,'계획'", "plan_concepts,'[]'::jsonb",
            "topic,NULL", "level,NULL", "purpose,NULL", "status,'NOT_PLANNED'"}, quoteCharacter = '"')
    @DisplayName("단일 코스에 계획을 넣거나 필수 입력을 빼거나 짜기 전으로 두면 거절한다")
    void rejectsMixedSingleCourseColumns(String column, String expression) {
        // given
        long id = single(owner());

        // when & then
        assertConstraint("23514", "courses_roadmap_id_check", () -> updateExpression("courses", id, column, expression));
    }

    @ParameterizedTest(name = "로드맵 코스의 잘못된 칸: {0}")
    @CsvSource(value = {"position,NULL", "plan_description,NULL", "plan_concepts,NULL", "topic,'주제'",
            "level,'BEGINNER'", "purpose,'WORK'", "memo,'메모'"}, quoteCharacter = '"')
    @DisplayName("로드맵의 코스는 계획을 모두 담고 입력 칸은 비워야 한다")
    void rejectsMixedRoadmapCourseColumns(String column, String expression) {
        // given
        long user = owner();
        long id = planned(user, roadmap(user), 1);

        // when & then
        assertConstraint("23514", "courses_roadmap_id_check", () -> updateExpression("courses", id, column, expression));
    }

    @ParameterizedTest(name = "상태 {0}, 시작 {1}, 완료 {2}")
    @CsvSource({"REVIEWING,false,false", "LEARNING,true,false", "COMPLETED,true,true"})
    @DisplayName("코스 상태에 맞는 시작과 완료 시각을 저장한다")
    void storesCourseStatusWithTimestamps(String status, boolean started, boolean completed) {
        // given
        long id = single(owner());

        // when
        setCourseStatus(id, status, started, completed);

        // then
        assertThat(jdbc.queryForObject("SELECT status FROM courses WHERE id = ?", String.class, id)).isEqualTo(status);
    }

    @ParameterizedTest(name = "상태 {0}, 시작 {1}, 완료 {2}")
    @CsvSource({"NOT_PLANNED,true,false,started_at", "REVIEWING,true,false,started_at",
            "LEARNING,false,false,started_at", "COMPLETED,false,true,started_at",
            "REVIEWING,false,true,completed_at", "LEARNING,true,true,completed_at",
            "COMPLETED,true,false,completed_at"})
    @DisplayName("상태와 시작 또는 완료 시각이 어긋나면 거절한다")
    void rejectsInconsistentCourseTimestamps(String status, boolean started, boolean completed, String column) {
        // given
        long user = owner();
        long id = planned(user, roadmap(user), 1);

        // when & then
        assertConstraint("23514", "courses_" + column + "_check", () -> setCourseStatus(id, status, started, completed));
    }

    @Test
    @DisplayName("로드맵 코스의 순서 0은 거절한다")
    void rejectsZeroCoursePosition() {
        // given
        long user = owner();
        long roadmap = roadmap(user);

        // when & then
        assertConstraint("23514", "courses_position_check", () -> planned(user, roadmap, 0));
    }

    @Test
    @DisplayName("같은 로드맵 안의 같은 코스 순서는 거절한다")
    void rejectsDuplicateCoursePosition() {
        // given
        long user = owner();
        long roadmap = roadmap(user);
        planned(user, roadmap, 1);

        // when & then
        assertConstraint("23505", "courses_roadmap_id_position_key", () -> planned(user, roadmap, 1));
    }

    @Test
    @DisplayName("다른 로드맵은 같은 코스 순서를 쓸 수 있다")
    void allowsSamePositionAcrossRoadmaps() {
        // given
        long user = owner();
        planned(user, roadmap(user), 1);
        long otherRoadmap = roadmap(user);

        // when
        long id = planned(user, otherRoadmap, 1);

        // then
        assertThat(jdbc.queryForObject("SELECT roadmap_id FROM courses WHERE id = ?", Long.class, id)).isEqualTo(otherRoadmap);
    }

    @ParameterizedTest(name = "{0}.{1}: {2}")
    @MethodSource("otherInputCombinations")
    @DisplayName("기타 선택과 기타 글의 유무가 어긋나면 거절한다")
    void rejectsInconsistentOtherInput(String table, String column, boolean other, boolean text) {
        // given
        long id = inputRow(table);

        // when & then
        assertConstraint("23514", table + "_" + column + "_other_check", () -> jdbc.update(
                "UPDATE " + table + " SET " + column + " = ?, " + column + "_other = ?, updated_at = now() WHERE id = ?",
                other ? "OTHER" : column.equals("level") ? "BEGINNER" : "WORK", text ? "기타" : null, id));
    }

    @ParameterizedTest(name = "{0}.{1} = {2}")
    @MethodSource("supportedInputValues")
    @DisplayName("수준과 목적의 모든 계약 값을 저장한다")
    void storesSupportedInputValues(String table, String column, String value) {
        // given
        long id = inputRow(table);

        // when
        jdbc.update("UPDATE " + table + " SET " + column + " = ?, " + column + "_other = ?, updated_at = now() WHERE id = ?",
                value, value.equals("OTHER") ? "직접 입력" : null, id);

        // then
        assertThat(jdbc.queryForObject("SELECT " + column + " FROM " + table + " WHERE id = ?", String.class, id)).isEqualTo(value);
    }

    @ParameterizedTest(name = "{0}.{1}")
    @CsvSource({"roadmaps,status", "roadmaps,level", "roadmaps,purpose", "courses,status", "courses,level", "courses,purpose"})
    @DisplayName("목록 밖의 상태 수준 목적은 거절한다")
    void rejectsUnsupportedValues(String table, String column) {
        // given
        long id = inputRow(table);

        // when & then
        assertConstraint("23514", table + "_" + column + "_check", () -> update(table, id, column, "UNKNOWN"));
    }

    @ParameterizedTest(name = "로드맵 상태 {0}")
    @ValueSource(strings = {"REVIEWING", "IN_PROGRESS", "COMPLETED"})
    @DisplayName("로드맵의 모든 계약 상태를 저장한다")
    void storesRoadmapStatuses(String status) {
        // given
        long id = roadmap(owner());

        // when
        update("roadmaps", id, "status", status);

        // then
        assertThat(jdbc.queryForObject("SELECT status FROM roadmaps WHERE id = ?", String.class, id)).isEqualTo(status);
    }

    @ParameterizedTest(name = "{0}.{1} 상한 {2}")
    @MethodSource("limitedColumns")
    @DisplayName("사용자 글은 글자 수 상한까지 저장한다")
    void storesTextAtLengthLimit(String table, String column, int limit) {
        // given
        long id = otherInputRow(table);
        String text = "가".repeat(limit);

        // when
        update(table, id, column, text);

        // then
        assertThat(jdbc.queryForObject("SELECT " + column + " FROM " + table + " WHERE id = ?", String.class, id)).isEqualTo(text);
    }

    @ParameterizedTest(name = "{0}.{1} 상한 {2}")
    @MethodSource("limitedColumns")
    @DisplayName("사용자 글은 글자 수 상한을 한 글자 넘으면 거절한다")
    void rejectsTextAboveLengthLimit(String table, String column, int limit) {
        // given
        long id = otherInputRow(table);

        // when & then
        assertConstraint("23514", table + "_" + column + "_check", () -> update(table, id, column, "가".repeat(limit + 1)));
    }

    @ParameterizedTest(name = "{0}.{1}: [{2}]")
    @MethodSource("blankColumns")
    @DisplayName("글 칸은 빈 글과 공백뿐인 글을 거절한다")
    void rejectsBlankText(String table, String column, String blank) {
        // given
        long id = textRow(table, column);

        // when & then
        assertConstraint("23514", table + "_" + column + "_not_blank", () -> update(table, id, column, blank));
    }

    @ParameterizedTest(name = "{0}.{1}")
    @CsvSource({"roadmaps,description", "courses,plan_description", "lessons,title", "lessons,description", "materials,body"})
    @DisplayName("생성된 글에는 길이 상한을 두지 않는다")
    void storesLongGeneratedText(String table, String column) {
        // given
        long id = textRow(table, column);
        String text = "긴".repeat(5000);

        // when
        update(table, id, column, text);

        // then
        assertThat(jdbc.queryForObject("SELECT " + column + " FROM " + table + " WHERE id = ?", String.class, id)).isEqualTo(text);
    }

    @ParameterizedTest(name = "강의 순서 {0}")
    @ValueSource(ints = {0, 11})
    @DisplayName("강의 순서는 1부터 10까지여야 한다")
    void rejectsLessonPositionOutsideRange(int position) {
        // given
        long course = single(owner());

        // when & then
        assertConstraint("23514", "lessons_position_check", () -> lesson(course, position));
    }

    @ParameterizedTest(name = "강의 순서 {0}")
    @ValueSource(ints = {1, 10})
    @DisplayName("강의 순서의 양 끝 경계는 저장한다")
    void storesLessonPositionBoundaries(int position) {
        // given
        long course = single(owner());

        // when
        long id = lesson(course, position);

        // then
        assertThat(jdbc.queryForObject("SELECT position FROM lessons WHERE id = ?", Integer.class, id)).isEqualTo(position);
    }

    @Test
    @DisplayName("같은 코스의 같은 강의 순서는 거절한다")
    void rejectsDuplicateLessonPosition() {
        // given
        long course = single(owner());
        lesson(course, 1);

        // when & then
        assertConstraint("23505", "lessons_course_id_position_key", () -> lesson(course, 1));
    }

    @Test
    @DisplayName("같은 강의의 강의 자료 둘은 거절한다")
    void rejectsDuplicateMaterial() {
        // given
        long lesson = lesson(single(owner()), 1);
        material(lesson);

        // when & then
        assertConstraint("23505", "materials_lesson_id_key", () -> material(lesson));
    }

    @Test
    @DisplayName("다룰 개념과 숙련도 반영 내역에는 JSON 모양 검사를 두지 않는다")
    void allowsJsonWithoutShapeChecks() {
        // given
        long user = owner();
        long course = planned(user, roadmap(user), 1);
        long lesson = lesson(course, 1);
        updateExpression("courses", course, "plan_concepts", "'{}'::jsonb");
        updateExpression("courses", course, "mastery_report", "'42'::jsonb");

        // when
        updateExpression("lessons", lesson, "concepts", "'null'::jsonb");

        // then
        assertThat(jdbc.queryForObject("SELECT concepts::text FROM lessons WHERE id = ?", String.class, lesson)).isEqualTo("null");
        assertThat(jdbc.queryForObject("SELECT mastery_report::text FROM courses WHERE id = ?", String.class, course)).isEqualTo("42");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"roadmaps", "courses", "lessons", "materials"})
    @DisplayName("배우는 테이블에는 필수 공통 시각 둘과 계약 색인만 있다")
    void preservesAuditingColumnsAndIndexes(String table) {
        // when
        var timestamps = jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ?
                AND column_name IN ('created_at', 'updated_at') AND data_type = 'timestamp with time zone'
                AND is_nullable = 'NO' AND column_default IS NULL ORDER BY column_name
                """, String.class, table);

        // then
        assertThat(timestamps).containsExactly("created_at", "updated_at");
        String[] indexes = switch (table) {
            case "roadmaps" -> new String[]{"roadmaps_pkey", "roadmaps_user_id_idx"};
            case "courses" -> new String[]{"courses_pkey", "courses_roadmap_id_position_key", "courses_user_id_idx"};
            case "lessons" -> new String[]{"lessons_pkey", "lessons_course_id_position_key"};
            case "materials" -> new String[]{"materials_pkey", "materials_lesson_id_key"};
            default -> throw new IllegalArgumentException(table);
        };
        assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename = ?", String.class, table))
                .containsExactlyInAnyOrder(indexes);
    }

    private long owner() {
        return users.save(UserFixtures.user("owner@example.com")).getId();
    }

    private long roadmap(long user) {
        return jdbc.queryForObject("""
                INSERT INTO roadmaps (user_id, title, description, status, topic, level, purpose, created_at, updated_at)
                VALUES (?, '로드맵', '설명', 'REVIEWING', '주제', 'BEGINNER', 'WORK', now(), now()) RETURNING id
                """, Long.class, user);
    }

    private long single(long user) {
        return jdbc.queryForObject("""
                INSERT INTO courses (user_id, title, status, topic, level, purpose, created_at, updated_at)
                VALUES (?, '단일 코스', 'REVIEWING', '주제', 'BEGINNER', 'WORK', now(), now()) RETURNING id
                """, Long.class, user);
    }

    private long planned(long user, long roadmap, int position) {
        return jdbc.queryForObject("""
                INSERT INTO courses (user_id, roadmap_id, position, title, status, plan_description, plan_concepts, created_at, updated_at)
                VALUES (?, ?, ?, '계획 코스', 'NOT_PLANNED', '계획 설명', '[]', now(), now()) RETURNING id
                """, Long.class, user, roadmap, position);
    }

    private long lesson(long course, int position) {
        return jdbc.queryForObject("""
                INSERT INTO lessons (course_id, position, title, description, concepts, created_at, updated_at)
                VALUES (?, ?, '강의', '설명', '[]', now(), now()) RETURNING id
                """, Long.class, course, position);
    }

    private long material(long lesson) {
        return jdbc.queryForObject("""
                INSERT INTO materials (lesson_id, body, created_at, updated_at)
                VALUES (?, '본문', now(), now()) RETURNING id
                """, Long.class, lesson);
    }

    private long inputRow(String table) {
        long user = owner();
        return table.equals("roadmaps") ? roadmap(user) : single(user);
    }

    private long otherInputRow(String table) {
        long id = inputRow(table);
        jdbc.update("UPDATE " + table + " SET level = 'OTHER', level_other = '수준', purpose = 'OTHER', purpose_other = '목적', updated_at = now() WHERE id = ?", id);
        return id;
    }

    private long textRow(String table, String column) {
        if (table.equals("roadmaps") || table.equals("courses") && !column.equals("plan_description")) return otherInputRow(table);
        long user = owner();
        if (table.equals("courses")) return planned(user, roadmap(user), 1);
        long lesson = lesson(single(user), 1);
        return table.equals("lessons") ? lesson : material(lesson);
    }

    private void setCourseStatus(long id, String status, boolean started, boolean completed) {
        jdbc.update("UPDATE courses SET status = ?, started_at = " + (started ? "now()" : "NULL")
                + ", completed_at = " + (completed ? "now()" : "NULL") + ", updated_at = now() WHERE id = ?", status, id);
    }

    private void update(String table, long id, String column, Object value) {
        jdbc.update("UPDATE " + table + " SET " + column + " = ?, updated_at = now() WHERE id = ?", value, id);
    }

    private void updateExpression(String table, long id, String column, String expression) {
        jdbc.update("UPDATE " + table + " SET " + column + " = " + expression + ", updated_at = now() WHERE id = ?", id);
    }

    private static void assertConstraint(String state, String constraint, Runnable operation) {
        assertThatThrownBy(operation::run).rootCause().isInstanceOfSatisfying(SQLException.class, sql -> {
            assertThat(sql.getSQLState()).isEqualTo(state);
            assertThat(sql.getMessage()).contains(constraint);
        });
    }

    static Stream<Arguments> otherInputCombinations() {
        return Stream.of("roadmaps", "courses").flatMap(table -> Stream.of("level", "purpose")
                .flatMap(column -> Stream.of(Arguments.of(table, column, true, false), Arguments.of(table, column, false, true))));
    }

    static Stream<Arguments> supportedInputValues() {
        return Stream.of("roadmaps", "courses").flatMap(table -> Stream.concat(
                Stream.of("BEGINNER", "BASICS", "FAMILIAR", "OTHER").map(value -> Arguments.of(table, "level", value)),
                Stream.of("WORK", "INTERVIEW", "GENERAL", "OTHER").map(value -> Arguments.of(table, "purpose", value))));
    }

    static Stream<Arguments> limitedColumns() {
        return Stream.of("roadmaps", "courses").flatMap(table -> Stream.of(
                Arguments.of(table, "topic", 100), Arguments.of(table, "title", 100),
                Arguments.of(table, "level_other", 50), Arguments.of(table, "purpose_other", 50), Arguments.of(table, "memo", 500)));
    }

    static Stream<Arguments> blankColumns() {
        return Stream.of("roadmaps,title", "roadmaps,description", "roadmaps,topic", "roadmaps,level_other", "roadmaps,purpose_other", "roadmaps,memo",
                        "courses,title", "courses,topic", "courses,level_other", "courses,purpose_other", "courses,memo", "courses,plan_description",
                        "lessons,title", "lessons,description", "materials,body")
                .map(pair -> pair.split(","))
                .flatMap(pair -> Stream.of("", "   ", "\t\n").map(blank -> Arguments.of(pair[0], pair[1], blank)));
    }
}
