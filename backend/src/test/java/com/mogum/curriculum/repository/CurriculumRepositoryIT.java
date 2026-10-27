package com.mogum.curriculum.repository;

import com.mogum.account.domain.User;
import com.mogum.account.domain.UserRole;
import com.mogum.account.repository.UserRepository;
import com.mogum.common.persistence.AuditedEntity;
import com.mogum.curriculum.domain.*;
import com.mogum.support.PostgresTestConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(CurriculumRepositoryIT.TestClockConfiguration.class)
class CurriculumRepositoryIT extends PostgresTestConfiguration {
    private static final Instant NOW = Instant.parse("2026-10-28T01:02:03.123456Z");
    @Autowired private CurriculumGroupRepository groups;
    @Autowired private CurriculumStepRepository steps;
    @Autowired private CurriculumRepository curriculums;
    @Autowired private LessonRepository lessons;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;
    private final List<Long> userIds = new ArrayList<>();
    private TransactionTemplate transactions;
    private User owner;
    private CurriculumGroup group;
    private CurriculumStep step;
    private Curriculum curriculum;
    private Lesson lesson;

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
        owner = createUser();
        group = groups.save(CurriculumGroup.plan(owner.getId(), "전체 주제", "전체 목적", "묶음 제목"));
        step = steps.save(CurriculumStep.of(group, 1, "단계 제목", "단계 범위", "단계 주제", "단계 목적"));
        curriculum = curriculums.save(Curriculum.forStep(step));
        lesson = lessons.save(Lesson.of(curriculum, 1, "강의 제목", "강의 목표", List.of("둘째", "첫째", "둘째")));
    }

    @AfterEach
    void cleanUp() {
        // JVM이 공유하는 DB에서 이 테스트가 만든 회원의 행만 자식부터 지운다.
        for (Long userId : userIds) {
            jdbc.update("DELETE FROM lessons WHERE curriculum_id IN (SELECT id FROM curriculums WHERE user_id = ?)", userId);
            jdbc.update("DELETE FROM curriculums WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM curriculum_steps WHERE group_id IN (SELECT id FROM curriculum_groups WHERE user_id = ?)", userId);
            jdbc.update("DELETE FROM curriculum_groups WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    @Test
    void 같은_묶음의_같은_단계_순서는_거절한다() {
        assertConstraint("23505", "curriculum_steps_group_order_key", () ->
                steps.save(CurriculumStep.of(group, 1, "다른 제목", "다른 범위", "다른 주제", null)));
    }

    @Test
    void 같은_단계에_커리큘럼_둘은_거절한다() {
        assertConstraint("23505", "curriculums_step_id_key", () -> curriculums.save(Curriculum.forStep(step)));
    }

    @Test
    void 단계가_없는_독립_커리큘럼은_여럿_저장한다() {
        var first = curriculums.save(Curriculum.single(owner.getId(), "첫 주제", null, "첫 제목"));
        var second = curriculums.save(Curriculum.single(owner.getId(), "둘째 주제", null, "둘째 제목"));

        assertNotEquals(first.getId(), second.getId());
        assertNull(curriculums.findById(first.getId()).orElseThrow().getStep());
        assertNull(curriculums.findById(second.getId()).orElseThrow().getStep());
    }

    @Test
    void 저장한_단계의_입력과_제목_묶음의_주인을_복사한다() {
        var otherOwner = createUser();
        var otherGroup = groups.save(CurriculumGroup.plan(otherOwner.getId(), "다른 전체 주제", null, "다른 묶음 제목"));
        var otherStep = steps.save(CurriculumStep.of(otherGroup, 1, "다른 단계 제목", "범위", "다른 단계 주제", "다른 단계 목적"));

        Long id = transactions.execute(status -> {
            var persistedStep = steps.findById(otherStep.getId()).orElseThrow();
            assertFalse(Hibernate.isInitialized(persistedStep.getGroup()));
            return curriculums.save(Curriculum.forStep(persistedStep)).getId();
        });

        var reloaded = curriculums.findById(id).orElseThrow();
        assertEquals(otherOwner.getId(), reloaded.getUserId());
        assertNotEquals(owner.getId(), reloaded.getUserId());
        assertEquals("다른 단계 제목", reloaded.getTitle());
        assertEquals("다른 단계 주제", reloaded.getTopic());
        assertEquals("다른 단계 목적", reloaded.getPurpose());
        assertEquals(CurriculumStatus.DRAFT, reloaded.getStatus());
        assertEquals(otherStep.getId(), reloaded.getStep().getId());
        assertEquals(otherOwner.getId(), jdbc.queryForObject("SELECT user_id FROM curriculums WHERE id = ?", Long.class, id));
    }

    @ParameterizedTest
    @CsvSource({"curriculum_groups,user_id,users", "curriculum_steps,group_id,curriculum_groups",
            "curriculums,user_id,users", "curriculums,step_id,curriculum_steps", "lessons,curriculum_id,curriculums"})
    void 없는_부모는_FK로_거절한다(String table, String column, String parent) {
        assertConstraint("23503", table + "_" + column + "_fkey", () -> update(table, column, -1L));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + parent + " WHERE id = -1", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"curriculums", "curriculum_groups", "curriculum_steps"})
    void 주제는_한글_100자를_저장하고_101자는_거절한다(String table) {
        update(table, "topic", "가".repeat(100));
        assertEquals("가".repeat(100), value(table, "topic"));
        assertConstraint("23514", table + "_topic_length", () -> update(table, "topic", "가".repeat(101)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"curriculums", "curriculum_groups", "curriculum_steps"})
    void 목적은_NULL과_한글_200자를_저장하고_201자는_거절한다(String table) {
        update(table, "purpose", null);
        assertNull(value(table, "purpose"));
        update(table, "purpose", "나".repeat(200));
        assertEquals("나".repeat(200), value(table, "purpose"));
        assertConstraint("23514", table + "_purpose_length", () -> update(table, "purpose", "나".repeat(201)));
    }

    @ParameterizedTest
    @CsvSource({"curriculums,topic", "curriculum_groups,topic", "curriculum_steps,topic",
            "curriculums,purpose", "curriculum_groups,purpose", "curriculum_steps,purpose",
            "curriculums,title", "curriculum_groups,title", "curriculum_steps,title",
            "curriculum_steps,scope", "lessons,title", "lessons,objective"})
    void 주제_목적_제목류는_빈값과_공백만인_값을_거절한다(String table, String column) {
        for (String blank : List.of("", "   ", "\t\n")) {
            assertConstraint("23514", table + "_" + column + "_not_blank", () -> update(table, column, blank));
        }
    }

    @ParameterizedTest
    @CsvSource({"curriculums,title", "curriculum_groups,title", "curriculum_steps,title",
            "curriculum_steps,scope", "lessons,title", "lessons,objective"})
    void 제목류_길이_상한은_아직_두지_않는다(String table, String column) {
        update(table, column, "긴".repeat(5000));
        assertEquals("긴".repeat(5000), value(table, column));
    }

    @ParameterizedTest
    @ValueSource(strings = {"curriculums", "lessons"})
    void 목록에_없는_상태는_거절한다(String table) {
        assertConstraint("23514", table + "_status_check", () -> update(table, "status", "GENERATING"));
    }

    @ParameterizedTest
    @CsvSource({"curriculums,DRAFT", "curriculums,ACTIVE", "curriculums,COMPLETED",
            "lessons,NOT_STARTED", "lessons,READY", "lessons,COMPLETED"})
    void 계약의_상태값을_모두_저장할_수_있다(String table, String status) {
        update(table, "status", status);
        assertEquals(status, value(table, "status"));
    }

    @Test
    void 같은_커리큘럼의_같은_강의_순서는_거절한다() {
        assertConstraint("23505", "lessons_curriculum_order_key", () ->
                lessons.save(Lesson.of(curriculum, 1, "다른 강의", "다른 목표", List.of())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"lessons", "curriculum_steps"})
    void 순서_0은_거절한다(String table) {
        assertConstraint("23514", table + "_order_no_check", () -> update(table, "order_no", 0));
    }

    @ParameterizedTest
    @CsvSource({"curriculums,lessons_curriculum_id_fkey", "curriculum_groups,curriculum_steps_group_id_fkey",
            "curriculum_steps,curriculums_step_id_fkey"})
    void 자식이_있는_부모는_삭제하지_못한다(String table, String constraint) {
        assertConstraint("23503", constraint, () -> jdbc.update("DELETE FROM " + table + " WHERE id = ?", id(table)));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id = ?", Integer.class, id(table)));
    }

    @ParameterizedTest
    @MethodSource("requiredColumns")
    void 필수_컬럼은_NULL을_거절한다(String table, String column) {
        assertConstraint("23502", null, () -> update(table, column, null));
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> requiredColumns() {
        return Stream.of(
                new String[]{"curriculum_groups", "user_id", "topic", "title", "created_at", "updated_at"},
                new String[]{"curriculum_steps", "group_id", "order_no", "title", "scope", "topic", "created_at", "updated_at"},
                new String[]{"curriculums", "user_id", "topic", "title", "status", "created_at", "updated_at"},
                new String[]{"lessons", "curriculum_id", "order_no", "title", "objective", "keywords", "status", "created_at", "updated_at"}
        ).flatMap(columns -> Stream.of(columns).skip(1)
                .map(column -> org.junit.jupiter.params.provider.Arguments.of(columns[0], column)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"curriculums", "curriculum_groups"})
    void 주인_조회용_단일_컬럼_인덱스가_있다(String table) {
        var definitions = jdbc.queryForList("SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = ?", String.class, table);
        assertTrue(definitions.stream().anyMatch(definition -> definition.endsWith(" USING btree (user_id)")), definitions.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"curriculum_groups", "curriculum_steps", "curriculums", "lessons"})
    void 저장한_생성과_수정_시각은_고정_시계의_시각이다(String table) {
        AuditedEntity reloaded = switch (table) {
            case "curriculum_groups" -> groups.findById(group.getId()).orElseThrow();
            case "curriculum_steps" -> steps.findById(step.getId()).orElseThrow();
            case "curriculums" -> curriculums.findById(curriculum.getId()).orElseThrow();
            case "lessons" -> lessons.findById(lesson.getId()).orElseThrow();
            default -> throw new IllegalArgumentException(table);
        };
        assertEquals(NOW, reloaded.getCreatedAt());
        assertEquals(NOW, reloaded.getUpdatedAt());
        jdbc.queryForObject("SELECT created_at, updated_at FROM " + table + " WHERE id = ?", (row, index) -> {
            assertEquals(NOW, row.getTimestamp("created_at").toInstant());
            assertEquals(NOW, row.getTimestamp("updated_at").toInstant());
            return true;
        }, id(table));
        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ?
                  AND column_name IN ('created_at', 'updated_at')
                  AND data_type = 'timestamp with time zone' AND column_default IS NULL
                """, Integer.class, table));
    }

    @Test
    void 단계의_묶음은_새_영속성_컨텍스트에서_지연_로딩한다() {
        transactions.executeWithoutResult(status -> {
            var reloaded = entityManager.find(CurriculumStep.class, step.getId());
            assertFalse(Hibernate.isInitialized(reloaded.getGroup()));
            assertEquals(group.getTitle(), reloaded.getGroup().getTitle());
        });
    }

    @Test
    void 커리큘럼의_단계는_새_영속성_컨텍스트에서_지연_로딩한다() {
        transactions.executeWithoutResult(status -> {
            var reloaded = entityManager.find(Curriculum.class, curriculum.getId());
            assertFalse(Hibernate.isInitialized(reloaded.getStep()));
            assertEquals(step.getTitle(), reloaded.getStep().getTitle());
        });
    }

    @Test
    void 강의의_커리큘럼은_새_영속성_컨텍스트에서_지연_로딩한다() {
        transactions.executeWithoutResult(status -> {
            var reloaded = entityManager.find(Lesson.class, lesson.getId());
            assertFalse(Hibernate.isInitialized(reloaded.getCurriculum()));
            assertEquals(curriculum.getTitle(), reloaded.getCurriculum().getTitle());
        });
    }

    @Test
    void 키워드_text_배열은_순서와_중복_값을_보존한다() {
        assertEquals(List.of("둘째", "첫째", "둘째"), lessons.findById(lesson.getId()).orElseThrow().getKeywords());
        assertEquals("text[]", jdbc.queryForObject("SELECT pg_typeof(keywords)::text FROM lessons WHERE id = ?", String.class, lesson.getId()));
    }

    @Test
    void 키워드_개수와_빈_원소_검사는_아직_두지_않는다() {
        var empty = lessons.save(Lesson.of(curriculum, 2, "빈 목록", "목표", List.of()));
        var blank = lessons.save(Lesson.of(curriculum, 3, "빈 원소", "목표", List.of("", " ")));
        assertEquals(List.of(), lessons.findById(empty.getId()).orElseThrow().getKeywords());
        assertEquals(List.of("", " "), lessons.findById(blank.getId()).orElseThrow().getKeywords());
    }

    @ParameterizedTest
    @CsvSource({"curriculum_groups_user_id_fkey,a", "curriculum_steps_group_id_fkey,a",
            "curriculums_user_id_fkey,a", "curriculums_step_id_fkey,r", "lessons_curriculum_id_fkey,a"})
    void 단계_연결만_RESTRICT이고_나머지_FK는_NO_ACTION이다(String constraint, String action) {
        assertEquals(action, jdbc.queryForObject("SELECT confdeltype::text FROM pg_constraint WHERE connamespace = 'public'::regnamespace AND conname = ?", String.class, constraint));
    }

    private User createUser() {
        var user = users.save(User.join("google", "curriculum-" + UUID.randomUUID(), "curriculum@example.com", UserRole.USER));
        userIds.add(user.getId());
        return user;
    }

    private long id(String table) {
        return switch (table) {
            case "curriculum_groups" -> group.getId();
            case "curriculum_steps" -> step.getId();
            case "curriculums" -> curriculum.getId();
            case "lessons" -> lesson.getId();
            default -> throw new IllegalArgumentException(table);
        };
    }

    private void update(String table, String column, Object value) {
        // 테스트 SQL도 Auditing 밖의 수정 시각을 명시한다.
        if (column.equals("updated_at")) {
            jdbc.update("UPDATE " + table + " SET updated_at = ? WHERE id = ?", value, id(table));
            return;
        }
        jdbc.update("UPDATE " + table + " SET " + column + " = ?, updated_at = ? WHERE id = ?",
                value, Timestamp.from(NOW), id(table));
    }

    private String value(String table, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM " + table + " WHERE id = ?", String.class, id(table));
    }

    private static void assertConstraint(String state, String constraint, Runnable operation) {
        var failure = assertThrows(RuntimeException.class, operation::run);
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                assertEquals(state, sql.getSQLState());
                if (constraint != null) assertTrue(sql.getMessage().contains(constraint), sql::getMessage);
                return;
            }
        }
        fail("PostgreSQL constraint failure was expected", failure);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfiguration {
        @Bean
        @Primary
        Clock curriculumTestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
