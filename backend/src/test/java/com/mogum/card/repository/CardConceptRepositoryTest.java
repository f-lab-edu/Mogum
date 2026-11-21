package com.mogum.card.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;

import org.hibernate.Hibernate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.card.domain.Card;
import com.mogum.card.domain.CardConceptFixtures;
import com.mogum.card.domain.CardConceptId;
import com.mogum.card.domain.CardFixtures;
import com.mogum.card.domain.ConceptFixtures;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.support.RepositoryTest;
import com.mogum.support.TestClock;

@RepositoryTest
class CardConceptRepositoryTest {
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private CourseRepository courseRepository;
	@Autowired
	private LessonRepository lessonRepository;
	@Autowired
	private CardRepository cardRepository;
	@Autowired
	private ConceptRepository conceptRepository;
	@Autowired
	private CardConceptRepository cardConceptRepository;
	@Autowired
	private TestClock testClock;

	@Test
	@DisplayName("카드의 개념 연결은 record 복합 키와 지연 로딩 부모 및 공용 시계의 시각으로 다시 읽힌다")
	void reloadsLinkWithCompositeIdAndLazyParents() {
		// given
		var card = savedCard();
		var concept = conceptRepository.save(ConceptFixtures.concept(
			card.getUserId(), "변수", "값을 담는 이름", new float[] {1, 2, 3}));
		var saved = cardConceptRepository.save(CardConceptFixtures.link(card, concept));

		// when
		var actual = cardConceptRepository.findById(new CardConceptId(card.getId(), concept.getId())).orElseThrow();

		// then
		assertThat(actual).isNotSameAs(saved);
		assertThat(actual.getId()).isEqualTo(new CardConceptId(card.getId(), concept.getId()));

		assertThat(actual.getCard().getId()).isEqualTo(card.getId());
		assertThat(actual.getConcept().getId()).isEqualTo(concept.getId());

		assertThat(Hibernate.isInitialized(actual.getCard())).isFalse();
		assertThat(Hibernate.isInitialized(actual.getConcept())).isFalse();

		assertThat(actual.getCreatedAt()).isEqualTo(testClock.instant());
		assertThat(actual.getUpdatedAt()).isEqualTo(testClock.instant());
		assertThat(actual.isNew()).isFalse();
	}

	@Test
	@DisplayName("레포지토리로 같은 카드와 개념의 새 연결 둘을 저장하면 기본 키 제약이 막는다")
	void rejectsDuplicateLinkOnSave() {
		// given
		var card = savedCard();
		var concept = conceptRepository.save(ConceptFixtures.concept(
			card.getUserId(), "변수", "값을 담는 이름", new float[] {1, 2, 3}));
		var saved = cardConceptRepository.save(CardConceptFixtures.link(card, concept));
		var duplicate = CardConceptFixtures.link(card, concept);

		// when & then
		assertThatThrownBy(() -> cardConceptRepository.save(duplicate))
			.isInstanceOf(DataIntegrityViolationException.class)
			.rootCause()
			.isInstanceOfSatisfying(SQLException.class, sql -> {
				assertThat(sql.getSQLState()).isEqualTo("23505");
				assertThat(sql.getMessage()).contains("card_concepts_pkey");
			});
		assertThat(cardConceptRepository.findById(saved.getId())).isPresent();
	}

	private Card savedCard() {
		var user = userRepository.save(UserFixtures.user("me@example.com"));
		var course = courseRepository.save(CourseFixtures.single(user.getId()));
		var lesson = lessonRepository.save(LessonFixtures.lesson(course, 1));
		return cardRepository.save(CardFixtures.card(user.getId(), lesson.getId(), 2, "질문", "답"));
	}
}
