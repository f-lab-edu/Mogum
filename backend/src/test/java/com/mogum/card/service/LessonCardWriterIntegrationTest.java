package com.mogum.card.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.card.domain.CardStatus;
import com.mogum.card.domain.ConceptFixtures;
import com.mogum.card.domain.FsrsState;
import com.mogum.card.repository.CardRepository;
import com.mogum.card.repository.ConceptRepository;
import com.mogum.card.service.LessonCardWriter.NewCard;
import com.mogum.card.service.LessonCardWriter.NewConcept;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.support.IntegrationTest;

@IntegrationTest
class LessonCardWriterIntegrationTest {
	@Autowired
	private LessonCardWriter lessonCardWriter;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private CourseRepository courseRepository;
	@Autowired
	private LessonRepository lessonRepository;
	@Autowired
	private ConceptRepository conceptRepository;
	@Autowired
	private CardRepository cardRepository;
	@Autowired
	private PlatformTransactionManager platformTransactionManager;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	@DisplayName("기준값 이상이면 기존 개념에 붙이고 처음 이름과 정의를 보존한다")
	void linksExistingConceptWithoutChangingIt() {
		// given
		long userId = userRepository.save(UserFixtures.user("me@example.com")).getId();
		long lessonId = lesson(userId);
		var existing = conceptRepository.save(ConceptFixtures.concept(userId, "격리 수준", "처음 정의",
			new float[] {4, 3}));
		var cards = List.of(new NewCard("질문", "답", List.of(new NewConcept("트랜잭션 격리 수준", "새 정의",
			new float[] {1, 0}))));
		// when
		write(userId, lessonId, cards);
		// then
		assertThat(linkedConceptIds()).containsExactly(existing.getId());
		var actual = conceptRepository.findById(existing.getId()).orElseThrow();
		assertThat(actual.getName()).isEqualTo("격리 수준");
		assertThat(actual.getDefinition()).isEqualTo("처음 정의");
		assertThat(actual.getEmbedding()).containsExactly(4, 3);
		assertThat(count("concepts")).isEqualTo(1);
	}

	@Test
	@DisplayName("가장 가까운 기존 개념도 기준값 미만이면 이름이 같아도 새 개념을 넣는다")
	void addsDifferentConceptEvenWithSameName() {
		// given
		long userId = userRepository.save(UserFixtures.user("me@example.com")).getId();
		long lessonId = lesson(userId);
		var existing = conceptRepository.save(ConceptFixtures.concept(userId, "격리 수준", "다른 정의",
			new float[] {0, 1}));
		// when
		write(userId, lessonId, cards("격리 수준", new float[] {1, 0}));
		// then
		assertThat(count("concepts")).isEqualTo(2);
		assertThat(linkedConceptIds()).doesNotContain(existing.getId()).hasSize(1);
		var actual = conceptRepository.findById(linkedConceptIds().getFirst()).orElseThrow();
		assertThat(actual.getUserId()).isEqualTo(userId);
		assertThat(actual.getName()).isEqualTo("격리 수준");
		assertThat(actual.getDefinition()).isEqualTo("정의");
		assertThat(actual.getEmbedding()).containsExactly(1, 0);
	}

	@Test
	@DisplayName("다른 사용자에게 같은 개념이 있어도 자신의 사전에 새로 넣는다")
	void createsOwnConceptInsteadOfLinkingOtherUser() {
		// given
		long userId = userRepository.save(UserFixtures.user("me@example.com")).getId();
		long otherId = userRepository.save(UserFixtures.user("other@example.com")).getId();
		long lessonId = lesson(userId);
		var other = conceptRepository.save(ConceptFixtures.concept(otherId, "격리 수준", "정의", new float[] {1, 0}));
		// when
		write(userId, lessonId, cards("격리 수준", new float[] {1, 0}));
		// then
		assertThat(count("concepts")).isEqualTo(2);
		assertThat(linkedConceptIds()).doesNotContain(other.getId()).hasSize(1);
		assertThat(conceptRepository.findById(linkedConceptIds().getFirst()).orElseThrow().getUserId())
			.isEqualTo(userId);
	}

	@Test
	@DisplayName("같은 저장에서 앞 카드에 새로 넣은 개념을 뒤 카드가 재사용한다")
	void reusesConceptAddedEarlierInSameSave() {
		// given
		long userId = userRepository.save(UserFixtures.user("me@example.com")).getId();
		long lessonId = lesson(userId);
		var cards = List.of(
			new NewCard("첫 질문", "첫 답", List.of(new NewConcept("격리 수준", "첫 정의", new float[] {1, 0}))),
			new NewCard("둘째 질문", "둘째 답", List.of(new NewConcept("인덱스", "둘째 정의", new float[] {0, 1}))),
			new NewCard("셋째 질문", "셋째 답", List.of(new NewConcept("트랜잭션 격리 수준", "셋째 정의", new float[] {1, 0}))));
		// when
		write(userId, lessonId, cards);
		// then
		assertThat(count("concepts")).isEqualTo(2);
		var ids = linkedConceptIds();
		assertThat(ids).hasSize(3);
		assertThat(ids.get(2)).isEqualTo(ids.getFirst()).isNotEqualTo(ids.get(1));
		assertThat(conceptRepository.findById(ids.getFirst()).orElseThrow().getName()).isEqualTo("격리 수준");
	}

	@Test
	@DisplayName("한 카드의 두 개념이 같은 개념으로 판정되면 연결을 하나만 넣는다")
	void deduplicatesConceptLinksWithinCard() {
		// given
		long userId = userRepository.save(UserFixtures.user("me@example.com")).getId();
		long lessonId = lesson(userId);
		var cards = List.of(new NewCard("질문", "답", List.of(
			new NewConcept("격리 수준", "첫 정의", new float[] {1, 0}),
			new NewConcept("트랜잭션 격리 수준", "다음 정의", new float[] {4, 3}))));
		// when
		write(userId, lessonId, cards);
		// then
		assertThat(count("concepts")).isEqualTo(1);
		assertThat(count("card_concepts")).isEqualTo(1);
	}

	@Test
	@DisplayName("카드 순서는 1부터 목록대로이고 ACTIVE와 FSRS 처음 상태 및 빈 다음 볼 때로 저장한다")
	void writesOrderedHiddenCardsWithInitialFsrsState() {
		// given
		long userId = userRepository.save(UserFixtures.user("me@example.com")).getId();
		long lessonId = lesson(userId);
		var concepts = List.of(new NewConcept("개념", "정의", new float[] {1, 0}));
		var cards = List.of(new NewCard("첫 질문", "첫 답", concepts), new NewCard("둘째 질문", "둘째 답", concepts));
		// when
		write(userId, lessonId, cards);
		// then
		var ids = jdbcTemplate.queryForList("SELECT id FROM cards ORDER BY position", Long.class);
		assertThat(ids).hasSize(2);
		for (int index = 0; index < ids.size(); index++) {
			var actual = cardRepository.findById(ids.get(index)).orElseThrow();
			assertThat(actual.getUserId()).isEqualTo(userId);
			assertThat(actual.getLessonId()).isEqualTo(lessonId);
			assertThat(actual.getPosition()).isEqualTo(index + 1);
			assertThat(actual.getQuestion()).isEqualTo(cards.get(index).question());
			assertThat(actual.getAnswer()).isEqualTo(cards.get(index).answer());
			assertThat(actual.getStatus()).isEqualTo(CardStatus.ACTIVE);
			assertThat(actual.getFsrsState()).isEqualTo(FsrsState.LEARNING);
			assertThat(actual.getFsrsStep()).isZero();
			assertThat(actual.getDueAt()).isNull();
			assertThat(actual.getStability()).isNull();
			assertThat(actual.getDifficulty()).isNull();
			assertThat(actual.getLastReviewedAt()).isNull();
		}
	}

	@Test
	@DisplayName("호출자가 연 트랜잭션이 없으면 쓰기를 시작하지 않는다")
	void requiresCallerTransaction() {
		// when & then
		assertThatThrownBy(() -> lessonCardWriter.writeLessonCards(1, 1, cards("개념", new float[] {1, 0})))
			.isInstanceOf(IllegalTransactionStateException.class);
		assertThat(count("cards")).isZero();
		assertThat(count("concepts")).isZero();
	}

	@Test
	@DisplayName("호출자의 결과 저장이 롤백되면 카드와 개념 및 연결도 함께 롤백된다")
	void rollsBackWithCaller() {
		// given
		long userId = userRepository.save(UserFixtures.user("me@example.com")).getId();
		long lessonId = lesson(userId);
		var transactionTemplate = new TransactionTemplate(platformTransactionManager);
		// when
		transactionTemplate.executeWithoutResult(status -> {
			lessonCardWriter.writeLessonCards(userId, lessonId, cards("개념", new float[] {1, 0}));
			status.setRollbackOnly();
		});
		// then
		assertThat(count("cards")).isZero();
		assertThat(count("concepts")).isZero();
		assertThat(count("card_concepts")).isZero();
	}

	private long lesson(long userId) {
		var course = courseRepository.save(CourseFixtures.single(userId));
		return lessonRepository.save(LessonFixtures.lesson(course, 1)).getId();
	}

	private List<NewCard> cards(String name, float[] vector) {
		return List.of(new NewCard("질문", "답", List.of(new NewConcept(name, "정의", vector))));
	}

	private void write(long userId, long lessonId, List<NewCard> cards) {
		new TransactionTemplate(platformTransactionManager)
			.executeWithoutResult(status -> lessonCardWriter.writeLessonCards(userId, lessonId, cards));
	}

	private List<Long> linkedConceptIds() {
		return jdbcTemplate.queryForList("SELECT concept_id FROM card_concepts ORDER BY card_id, concept_id",
			Long.class);
	}

	private int count(String table) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
	}
}
