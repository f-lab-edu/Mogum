package com.mogum.card.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.card.domain.CardConceptFixtures;
import com.mogum.card.domain.CardFixtures;
import com.mogum.card.domain.Concept;
import com.mogum.card.domain.ConceptFixtures;
import com.mogum.card.domain.FsrsResult;
import com.mogum.card.domain.FsrsState;
import com.mogum.card.domain.Judgment;
import com.mogum.card.domain.Rating;
import com.mogum.card.domain.ReviewFixtures;
import com.mogum.card.repository.CardConceptRepository;
import com.mogum.card.repository.CardRepository;
import com.mogum.card.repository.ConceptRepository;
import com.mogum.card.repository.ReviewRepository;
import com.mogum.card.service.ConceptLookupService.RelatedConcept;
import com.mogum.config.ConceptProperties;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestClock;

@IntegrationTest
class ConceptLookupServiceIntegrationTest {

	@Autowired
	ConceptLookupService conceptLookupService;
	@Autowired
	UserRepository userRepository;
	@Autowired
	ConceptRepository conceptRepository;
	@Autowired
	CardRepository cardRepository;
	@Autowired
	CardConceptRepository cardConceptRepository;
	@Autowired
	ReviewRepository reviewRepository;
	@Autowired
	CourseRepository courseRepository;
	@Autowired
	LessonRepository lessonRepository;
	@Autowired
	ConceptProperties conceptProperties;
	@Autowired
	PlatformTransactionManager platformTransactionManager;
	@Autowired
	JdbcTemplate jdbcTemplate;
	@Autowired
	TestClock testClock;

	@Test
	@DisplayName("내 사전에 개념이 있으면 참이다")
	void detectsOwnConcepts() {
		// given
		long userId = user();
		concept(userId, "내 개념", 1, 0);

		// when
		var actual = conceptLookupService.hasConcepts(userId);

		// then
		assertThat(actual).isTrue();
	}

	@Test
	@DisplayName("빈 사전은 거짓이다")
	void detectsEmptyDictionary() {
		// given
		long userId = user();

		// when
		var actual = conceptLookupService.hasConcepts(userId);

		// then
		assertThat(actual).isFalse();
	}

	@Test
	@DisplayName("남의 사전에만 개념이 있으면 거짓이다")
	void ignoresOtherDictionaryWhenCheckingExistence() {
		// given
		long userId = user();
		concept(userRepository.save(UserFixtures.user("other@example.com")).getId(), "남의 개념", 1, 0);

		// when
		var actual = conceptLookupService.hasConcepts(userId);

		// then
		assertThat(actual).isFalse();
	}

	@Test
	@DisplayName("관련 개념은 높은 유사도 순으로 개수 제한까지 고르고 이름 정의 판정을 담는다")
	void ranksAndLimitsRelatedConceptsWithJudgments() {
		// given
		long userId = user();
		var weak = concept(userId, "중간", 4, 3);
		concept(userId, "멀리", -1, 0);
		var known = concept(userId, "가까움", 1, 0);
		reviewed(weak, 10);
		reviewed(known, 21);

		// when
		var actual = conceptLookupService.findRelated(userId, List.of(vector(1, 0)), 2, testClock.instant());

		// then
		assertThat(actual).containsExactly(new RelatedConcept("가까움", "가까움 정의", Judgment.WELL_KNOWN),
			new RelatedConcept("중간", "중간 정의", Judgment.SHAKY));
	}

	@Test
	@DisplayName("여러 질의의 최대 유사도로 정렬하고 중복 없이 동점은 작은 개념 ID 순이다")
	void ranksByBestQueryWithoutDuplicatesAndBreaksTiesById() {
		// given
		long userId = user();
		concept(userId, "중간", 4, 3);
		concept(userId, "둘째 질의", 0, 1);
		concept(userId, "첫째 질의", 1, 0);
		concept(userId, "반대", -1, -1);

		// when
		var actual = conceptLookupService.findRelated(userId,
			List.of(vector(1, 0), vector(0, 1), vector(0, 1)), 20, testClock.instant());

		// then
		assertThat(actual).extracting(RelatedConcept::name).containsExactly("둘째 질의", "첫째 질의", "중간", "반대");
		assertThat(actual).extracting(RelatedConcept::judgment).containsOnly(Judgment.NO_RECORD);
	}

	@Test
	@DisplayName("관련 개념에는 유사도 하한을 두지 않고 남의 더 가까운 개념은 제외한다")
	void includesLowSimilarityAndExcludesForeignConcepts() {
		// given
		long userId = user();
		concept(userId, "반대", -1, 0);
		concept(userRepository.save(UserFixtures.user("other@example.com")).getId(), "남의 것", 1, 0);

		// when
		var actual = conceptLookupService.findRelated(userId, List.of(vector(1, 0)), 20, testClock.instant());

		// then
		assertThat(actual).containsExactly(new RelatedConcept("반대", "반대 정의", Judgment.NO_RECORD));
	}

	@Test
	@DisplayName("빈 사전의 관련 개념은 빈 목록이다")
	void returnsNoRelatedConceptsForEmptyDictionary() {
		// given
		long userId = user();

		// when
		var actual = conceptLookupService.findRelated(userId, List.of(vector(1, 0)), 20, testClock.instant());

		// then
		assertThat(actual).isEmpty();
	}

	@Test
	@DisplayName("질의가 없으면 관련 개념은 빈 목록이다")
	void returnsNoRelatedConceptsForEmptyQueries() {
		// given
		long userId = user();
		concept(userId, "개념", 1, 0);

		// when
		var actual = conceptLookupService.findRelated(userId, List.of(), 20, testClock.instant());

		// then
		assertThat(actual).isEmpty();
	}

	@Test
	@DisplayName("정의마다 가장 가까운 개념의 판정을 붙이고 중복 정의와 불일치의 순서 및 길이를 보존한다")
	void preservesOrderAndLengthWithRepeatedAndUnmatchedDefinitions() {
		// given
		long userId = user();
		var known = concept(userId, "잘 앎", 1, 0);
		var weak = concept(userId, "흔들림", 0, 1);
		reviewed(known, 21);
		reviewed(weak, 10);

		// when
		var actual = conceptLookupService.judgeMatching(userId,
			List.of(vector(0, 1), vector(-1, -1), vector(1, 0), vector(0, 1)), testClock.instant());

		// then
		assertThat(actual).containsExactly(Judgment.SHAKY, Judgment.NO_RECORD, Judgment.WELL_KNOWN, Judgment.SHAKY);
	}

	@Test
	@DisplayName("설정된 같은 개념 기준값 아래인 가장 가까운 개념은 기록 없음이다")
	void usesConfiguredSameConceptThreshold() {
		// given
		long userId = user();
		var concept = concept(userId, "경계 아래", 7, 8);
		reviewed(concept, 21);
		assertThat(conceptProperties.sameThreshold()).isEqualTo(0.72);

		// when
		var actual = conceptLookupService.judgeMatching(userId, List.of(vector(1, 0)), testClock.instant());

		// then
		assertThat(actual).containsExactly(Judgment.NO_RECORD);
	}

	@Test
	@DisplayName("가장 가까운 개념의 동점도 쓰기 검색이 고르는 개념과 같은 판정을 돌려준다")
	void judgesSameConceptAsWriteSearchIncludingTies() {
		// given
		long userId = user();
		var weak = concept(userId, "먼저", 1, 0);
		var known = concept(userId, "나중", 1, 0);
		reviewed(weak, 10);
		reviewed(known, 21);
		var selected = new TransactionTemplate(platformTransactionManager).execute(status -> conceptRepository
			.findSameConcept(userId, vector(1, 0), conceptProperties.sameThreshold()).orElseThrow());
		assertThat(selected.getId()).isEqualTo(weak.getId());

		// when
		var actual = conceptLookupService.judgeMatching(userId, List.of(vector(1, 0)), testClock.instant());

		// then
		assertThat(actual).containsExactly(Judgment.SHAKY);
	}

	@Test
	@DisplayName("남의 동일한 벡터와는 맞추지 않는다")
	void doesNotMatchForeignConcepts() {
		// given
		long userId = user();
		var foreign = concept(userRepository.save(UserFixtures.user("other@example.com")).getId(), "남의 것", 1, 0);
		reviewed(foreign, 21);

		// when
		var actual = conceptLookupService.judgeMatching(userId, List.of(vector(1, 0)), testClock.instant());

		// then
		assertThat(actual).containsExactly(Judgment.NO_RECORD);
	}

	@Test
	@DisplayName("빈 사전에서는 받은 정의 수만큼 기록 없음이다")
	void returnsNoRecordForEveryDefinitionInEmptyDictionary() {
		// given
		long userId = user();

		// when
		var actual = conceptLookupService.judgeMatching(userId,
			List.of(vector(1, 0), vector(0, 1)), testClock.instant());

		// then
		assertThat(actual).containsExactly(Judgment.NO_RECORD, Judgment.NO_RECORD);
	}

	@Test
	@DisplayName("정의가 없으면 판정도 빈 목록이다")
	void returnsEmptyJudgmentsForEmptyDefinitions() {
		// given
		long userId = user();

		// when
		var actual = conceptLookupService.judgeMatching(userId, List.of(), testClock.instant());

		// then
		assertThat(actual).isEmpty();
	}

	@ParameterizedTest(name = "조회 {0}")
	@ValueSource(ints = {0, 1, 2})
	@DisplayName("세 조회 모두 사전 카드 연결 복습의 행과 수정 시각을 바꾸지 않는다")
	void leavesAllRowsAndAuditTimestampsUnchanged(int operation) {
		// given
		long userId = user();
		var concept = concept(userId, "개념", 1, 0);
		reviewed(concept, 21);
		var before = snapshot();
		testClock.advance(Duration.ofDays(1));

		// when
		switch (operation) {
			case 0 -> conceptLookupService.hasConcepts(userId);
			case 1 -> conceptLookupService.findRelated(userId, List.of(vector(1, 0)), 20, testClock.instant());
			case 2 -> conceptLookupService.judgeMatching(userId,
				List.of(vector(1, 0), vector(-1, 0)), testClock.instant());
			default -> throw new IllegalArgumentException("unknown operation");
		}

		// then
		assertThat(snapshot()).isEqualTo(before);
	}

	private long user() {
		return userRepository.save(UserFixtures.user("me@example.com")).getId();
	}

	private Concept concept(long userId, String name, float first, float second) {
		return conceptRepository.save(ConceptFixtures.concept(userId, name, name + " 정의", vector(first, second)));
	}

	private void reviewed(Concept concept, double stability) {
		var course = courseRepository.save(CourseFixtures.single(concept.getUserId()));
		var lesson = lessonRepository.save(LessonFixtures.lesson(course, 1));
		var card = CardFixtures.card(concept.getUserId(), lesson.getId(), 1, "질문", "답");
		card.applyReview(new FsrsResult(FsrsState.REVIEW, null, stability, 5.0,
			testClock.instant().plus(Duration.ofDays(21)), testClock.instant()));
		var saved = cardRepository.save(card);
		cardConceptRepository.save(CardConceptFixtures.link(saved, concept));
		reviewRepository.save(ReviewFixtures.review(saved, Rating.GOOD));
	}

	private List<List<Map<String, Object>>> snapshot() {
		return List.of(jdbcTemplate.queryForList("SELECT * FROM concepts ORDER BY id"),
			jdbcTemplate.queryForList("SELECT * FROM cards ORDER BY id"),
			jdbcTemplate.queryForList("SELECT * FROM card_concepts ORDER BY card_id, concept_id"),
			jdbcTemplate.queryForList("SELECT * FROM reviews ORDER BY id"));
	}

	private static float[] vector(float first, float second) {
		var vector = new float[3072];
		vector[0] = first;
		vector[1] = second;
		return vector;
	}
}
