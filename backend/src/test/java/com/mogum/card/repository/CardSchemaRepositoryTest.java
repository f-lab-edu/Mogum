package com.mogum.card.repository;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@RepositoryTest
class CardSchemaRepositoryTest {
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @ParameterizedTest(name = "{0}.{1}")
    @CsvSource({"cards,status", "cards,fsrs_state", "reviews,rating"})
    @DisplayName("카드 상태와 FSRS 상태 및 평가는 계약 밖의 값을 거절한다")
    void rejectsUnsupportedValues(String table, String column) {
        // given
        var graph = graph();
        long id = table.equals("cards") ? graph.card() : graph.review();

        // when & then
        assertConstraint("23514", table + "_" + column + "_check", () -> update(table, id, column, "UNKNOWN"));
    }

    @ParameterizedTest(name = "평가 {0}")
    @ValueSource(strings = {"AGAIN", "HARD", "GOOD", "EASY"})
    @DisplayName("FSRS의 네 평가를 모두 저장한다")
    void storesSupportedRatings(String rating) {
        // given
        var graph = graph();

        // when
        update("reviews", graph.review(), "rating", rating);

        // then
        assertThat(jdbc.queryForObject("SELECT rating FROM reviews WHERE id = ?", String.class, graph.review())).isEqualTo(rating);
    }

    @ParameterizedTest(name = "FSRS {0}, 단계 {1}")
    @CsvSource({"LEARNING,0", "REVIEW,", "RELEARNING,0"})
    @DisplayName("FSRS 상태와 단계가 맞으면 저장한다")
    void storesConsistentFsrsState(String state, Integer step) {
        // given
        var graph = graph();

        // when
        setFsrs(graph.card(), state, step);

        // then
        assertThat(jdbc.queryForMap("SELECT fsrs_state, fsrs_step FROM cards WHERE id = ?", graph.card()))
                .containsEntry("fsrs_state", state).containsEntry("fsrs_step", step);
    }

    @ParameterizedTest(name = "FSRS {0}, 단계 {1}")
    @CsvSource({"LEARNING,", "REVIEW,0", "RELEARNING,"})
    @DisplayName("FSRS 상태와 단계의 빈 값이 어긋나면 거절한다")
    void rejectsInconsistentFsrsState(String state, Integer step) {
        // given
        var graph = graph();

        // when & then
        assertConstraint("23514", "cards_fsrs_step_check", () -> setFsrs(graph.card(), state, step));
    }

    @Test
    @DisplayName("쓰는 중인 카드가 달린 강의는 SET NULL이 상태 검사에 걸려 지울 수 없다")
    void rejectsDeletingLessonWithActiveCard() {
        // given
        var graph = graph();
        jdbc.update("DELETE FROM materials WHERE id = ?", graph.material());

        // when & then
        assertConstraint("23514", "cards_lesson_id_check", () -> jdbc.update("DELETE FROM lessons WHERE id = ?", graph.lesson()));
        assertThat(jdbc.queryForObject("SELECT lesson_id FROM cards WHERE id = ?", Long.class, graph.card())).isEqualTo(graph.lesson());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lessons WHERE id = ?", Integer.class, graph.lesson())).isEqualTo(1);
    }

    @Test
    @DisplayName("코스를 계약 순서로 지우면 카드와 개념 연결 및 복습은 남고 강의 연결만 빈다")
    void deletesCourseInDocumentedOrderAndPreservesCards() {
        // given
        var graph = graph();
        var cardBefore = jdbc.queryForMap("SELECT question, answer, fsrs_state, fsrs_step, due_at, stability, difficulty, last_reviewed_at, created_at FROM cards WHERE id = ?", graph.card());
        var linksBefore = jdbc.queryForList("SELECT * FROM card_concepts");
        var reviewsBefore = jdbc.queryForList("SELECT * FROM reviews");

        // when
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update("UPDATE cards SET status = 'COURSE_DELETED', updated_at = now() WHERE lesson_id = ?", graph.lesson());
            jdbc.update("DELETE FROM materials WHERE lesson_id = ?", graph.lesson());
            jdbc.update("DELETE FROM lessons WHERE course_id = ?", graph.course());
            jdbc.update("DELETE FROM courses WHERE id = ?", graph.course());
        });

        // then
        assertThat(jdbc.queryForMap("SELECT status, lesson_id FROM cards WHERE id = ?", graph.card()))
                .containsEntry("status", "COURSE_DELETED").containsEntry("lesson_id", null);
        assertThat(jdbc.queryForMap("SELECT question, answer, fsrs_state, fsrs_step, due_at, stability, difficulty, last_reviewed_at, created_at FROM cards WHERE id = ?", graph.card()))
                .isEqualTo(cardBefore);
        assertThat(jdbc.queryForList("SELECT * FROM card_concepts")).isEqualTo(linksBefore);
        assertThat(jdbc.queryForList("SELECT * FROM reviews")).isEqualTo(reviewsBefore);
        for (String table : new String[]{"materials", "lessons", "courses"}) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM concepts", Integer.class)).isEqualTo(1);
    }

    @ParameterizedTest(name = "부모 {0}")
    @CsvSource({"courses,lessons_course_id_fkey", "cards,reviews_card_id_fkey", "concepts,card_concepts_concept_id_fkey"})
    @DisplayName("자식이 남은 부모는 먼저 지울 수 없다")
    void rejectsDeletingParentsBeforeChildren(String table, String constraint) {
        // given
        var graph = graph();
        long id = switch (table) {
            case "courses" -> graph.course();
            case "cards" -> graph.card();
            case "concepts" -> graph.concept();
            default -> throw new IllegalArgumentException(table);
        };
        if (table.equals("cards")) jdbc.update("DELETE FROM card_concepts WHERE card_id = ?", id);

        // when & then
        assertConstraint("23503", constraint, () -> jdbc.update("DELETE FROM " + table + " WHERE id = ?", id));
    }

    @Test
    @DisplayName("사용자가 카드를 지우는 순서로 지우면 개념과 코스는 남는다")
    void deletesCardInDocumentedOrder() {
        // given
        var graph = graph();

        // when
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update("DELETE FROM reviews WHERE card_id = ?", graph.card());
            jdbc.update("DELETE FROM card_concepts WHERE card_id = ?", graph.card());
            jdbc.update("DELETE FROM cards WHERE id = ?", graph.card());
        });

        // then
        for (String table : new String[]{"cards", "card_concepts", "reviews"}) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM concepts", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM courses", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 카드와 개념 연결은 둘 생길 수 없다")
    void rejectsDuplicateCardConcept() {
        // given
        var graph = graph();

        // when & then
        assertConstraint("23505", "card_concepts_pkey", () -> jdbc.update("""
                INSERT INTO card_concepts (card_id, concept_id, created_at, updated_at) VALUES (?, ?, now(), now())
                """, graph.card(), graph.concept()));
    }

    @Test
    @DisplayName("같은 강의의 같은 카드 순서는 거절한다")
    void rejectsDuplicateCardPosition() {
        // given
        var graph = graph();

        // when & then
        assertConstraint("23505", "cards_lesson_id_position_key", () -> card(graph.user(), graph.lesson(), 1));
    }

    @Test
    @DisplayName("카드 순서에는 문서에 없는 하한 검사를 두지 않는다")
    void allowsZeroCardPosition() {
        // given
        var graph = graph();

        // when
        update("cards", graph.card(), "position", 0);

        // then
        assertThat(jdbc.queryForObject("SELECT position FROM cards WHERE id = ?", Integer.class, graph.card())).isZero();
    }

    @Test
    @DisplayName("차원이 다른 임베딩과 같은 이름의 개념을 한 테이블에 저장한다")
    void storesEmbeddingsWithDifferentDimensions() {
        // given
        var graph = graph();

        // when
        long second = concept(graph.user(), "[1,2,3,4,5]");

        // then
        assertThat(jdbc.queryForList("SELECT vector_dims(embedding) FROM concepts ORDER BY id", Integer.class)).containsExactly(3, 5);
        assertThat(second).isNotEqualTo(graph.concept());
    }

    @Test
    @DisplayName("임베딩이 없는 개념은 거절한다")
    void rejectsNullEmbedding() {
        // given
        var graph = graph();

        // when & then
        assertConstraint("23502", "embedding", () -> concept(graph.user(), null));
    }

    @ParameterizedTest(name = "{0}.{1}: [{2}]")
    @MethodSource("blankColumns")
    @DisplayName("카드와 개념의 글은 공백만 있으면 거절한다")
    void rejectsBlankText(String table, String column, String text) {
        // given
        var graph = graph();
        long id = table.equals("cards") ? graph.card() : graph.concept();

        // when & then
        assertConstraint("23514", table + "_" + column + "_not_blank", () -> update(table, id, column, text));
    }

    @ParameterizedTest(name = "{0}.{1}")
    @CsvSource({"cards,question", "cards,answer", "concepts,name", "concepts,definition"})
    @DisplayName("카드와 개념의 생성된 글에는 길이 상한이 없다")
    void storesLongGeneratedText(String table, String column) {
        // given
        var graph = graph();
        long id = table.equals("cards") ? graph.card() : graph.concept();
        String text = "긴".repeat(5000);

        // when
        update(table, id, column, text);

        // then
        assertThat(jdbc.queryForObject("SELECT " + column + " FROM " + table + " WHERE id = ?", String.class, id)).isEqualTo(text);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"cards", "card_concepts", "reviews", "concepts"})
    @DisplayName("기억과 개념 테이블은 연결 테이블까지 필수 시각 둘과 계약 색인만 둔다")
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
            case "cards" -> new String[]{"cards_pkey", "cards_lesson_id_position_key", "cards_user_id_due_at_idx", "cards_lesson_id_idx"};
            case "card_concepts" -> new String[]{"card_concepts_pkey", "card_concepts_concept_id_idx"};
            case "reviews" -> new String[]{"reviews_pkey", "reviews_card_id_idx"};
            case "concepts" -> new String[]{"concepts_pkey", "concepts_user_id_idx"};
            default -> throw new IllegalArgumentException(table);
        };
        assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename = ?", String.class, table))
                .containsExactlyInAnyOrder(indexes);
    }

    @Test
    @DisplayName("강의를 가리키는 카드만 SET NULL이고 나머지 학습 모델 FK는 NO ACTION이다")
    void preservesForeignKeyDeleteActions() {
        // when
        var actions = jdbc.queryForList("""
                SELECT conname, confdeltype::text AS action FROM pg_constraint
                WHERE connamespace = 'public'::regnamespace AND contype = 'f'
                AND conrelid IN ('roadmaps'::regclass, 'courses'::regclass, 'lessons'::regclass, 'materials'::regclass,
                    'cards'::regclass, 'card_concepts'::regclass, 'reviews'::regclass, 'concepts'::regclass)
                """);

        // then
        assertThat(actions).hasSize(11).allSatisfy(row -> assertThat(row.get("action"))
                .isEqualTo(row.get("conname").equals("cards_lesson_id_fkey") ? "n" : "a"));
    }

    private Graph graph() {
        long user = users.save(UserFixtures.user("owner@example.com")).getId();
        long course = jdbc.queryForObject("""
                INSERT INTO courses (user_id, title, status, topic, level, purpose, created_at, updated_at)
                VALUES (?, '코스', 'REVIEWING', '주제', 'BEGINNER', 'WORK', now(), now()) RETURNING id
                """, Long.class, user);
        long lesson = jdbc.queryForObject("""
                INSERT INTO lessons (course_id, position, title, description, concepts, created_at, updated_at)
                VALUES (?, 1, '강의', '설명', '[]', now(), now()) RETURNING id
                """, Long.class, course);
        long material = jdbc.queryForObject("""
                INSERT INTO materials (lesson_id, body, created_at, updated_at)
                VALUES (?, '본문', now(), now()) RETURNING id
                """, Long.class, lesson);
        long card = card(user, lesson, 1);
        long concept = concept(user, "[1,2,3]");
        jdbc.update("INSERT INTO card_concepts (card_id, concept_id, created_at, updated_at) VALUES (?, ?, now(), now())", card, concept);
        long review = jdbc.queryForObject("""
                INSERT INTO reviews (card_id, rating, created_at, updated_at) VALUES (?, 'GOOD', now(), now()) RETURNING id
                """, Long.class, card);
        return new Graph(user, course, lesson, material, card, concept, review);
    }

    private long card(long user, long lesson, int position) {
        return jdbc.queryForObject("""
                INSERT INTO cards (user_id, lesson_id, position, question, answer, status, fsrs_state, fsrs_step, created_at, updated_at)
                VALUES (?, ?, ?, '질문', '답', 'ACTIVE', 'LEARNING', 0, now(), now()) RETURNING id
                """, Long.class, user, lesson, position);
    }

    private long concept(long user, String embedding) {
        return jdbc.queryForObject("""
                INSERT INTO concepts (user_id, name, definition, embedding, created_at, updated_at)
                VALUES (?, '같은 개념', '정의', CAST(? AS vector), now(), now()) RETURNING id
                """, Long.class, user, embedding);
    }

    private void setFsrs(long card, String state, Integer step) {
        jdbc.update("UPDATE cards SET fsrs_state = ?, fsrs_step = ?, updated_at = now() WHERE id = ?", state, step, card);
    }

    private void update(String table, long id, String column, Object value) {
        jdbc.update("UPDATE " + table + " SET " + column + " = ?, updated_at = now() WHERE id = ?", value, id);
    }

    private static void assertConstraint(String state, String constraint, Runnable operation) {
        assertThatThrownBy(operation::run).rootCause().isInstanceOfSatisfying(SQLException.class, sql -> {
            assertThat(sql.getSQLState()).isEqualTo(state);
            assertThat(sql.getMessage()).contains(constraint);
        });
    }

    static Stream<Arguments> blankColumns() {
        return Stream.of("cards,question", "cards,answer", "concepts,name", "concepts,definition")
                .map(pair -> pair.split(","))
                .flatMap(pair -> Stream.of("", "   ", "\t\n").map(blank -> Arguments.of(pair[0], pair[1], blank)));
    }

    private record Graph(long user, long course, long lesson, long material, long card, long concept, long review) { }
}
