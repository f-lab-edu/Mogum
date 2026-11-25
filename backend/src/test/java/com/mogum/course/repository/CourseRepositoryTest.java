package com.mogum.course.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.hibernate.Hibernate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.course.domain.Course;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.CourseStatus;
import com.mogum.course.domain.LearningInput;
import com.mogum.course.domain.Level;
import com.mogum.course.domain.MasteryReport;
import com.mogum.course.domain.PlannedConcept;
import com.mogum.course.domain.Purpose;
import com.mogum.course.domain.RoadmapFixtures;
import com.mogum.support.RepositoryTest;

@RepositoryTest
class CourseRepositoryTest {
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private RoadmapRepository roadmapRepository;
	@Autowired
	private CourseRepository courseRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@ParameterizedTest(name = "수준 {0}, 목적 {2}, 메모 {4}")
	@CsvSource({"BEGINNER,,WORK,,자바 예제", "BASICS,,INTERVIEW,,", "FAMILIAR,,GENERAL,,",
		"OTHER,실습 경험,OTHER,개인 프로젝트,"})
	@DisplayName("단일 코스는 입력과 검토 중 상태를 저장하고 계획과 학습 시각은 비운다")
	void reloadsSingleCourseWithInput(Level level, String levelOther, Purpose purpose,
		String purposeOther, String memo) {
		// given
		var user = userRepository.save(UserFixtures.user("me@example.com"));
		var input = new LearningInput("자바", level, levelOther, purpose, purposeOther, memo);
		var saved = courseRepository.save(CourseFixtures.single(user.getId(), input, "자바 기초"));

		// when
		var actual = courseRepository.findById(saved.getId()).orElseThrow();

		// then
		assertThat(actual).isNotSameAs(saved);
		assertThat(actual.getId()).isPositive();

		assertThat(actual.getUserId()).isEqualTo(user.getId());
		assertThat(actual.getTitle()).isEqualTo("자바 기초");
		assertThat(actual.getInput()).usingRecursiveComparison().isEqualTo(input);

		assertThat(actual.getStatus()).isEqualTo(CourseStatus.REVIEWING);
		assertThat(actual.getRoadmap()).isNull();
		assertThat(actual.getPosition()).isNull();
		assertThat(actual.getPlanDescription()).isNull();
		assertThat(actual.getPlanConcepts()).isNull();

		assertThat(actual.getStartedAt()).isNull();
		assertThat(actual.getLastLessonOpenedAt()).isNull();
		assertThat(actual.getCompletedAt()).isNull();
	}

	@ParameterizedTest(name = "내역 있음={0}")
	@ValueSource(booleans = {true, false})
	@DisplayName("반영 내역은 계약의 jsonb 키와 이름 순서를 그대로 저장하고 다시 읽는다")
	void reloadsMasteryReport(boolean populated) {
		// given
		var user = userRepository.save(UserFixtures.user("me@example.com"));
		var report = populated ? new MasteryReport(List.of("변수", "타입", "변수"), List.of("함수"))
			: new MasteryReport(List.of(), List.of());
		var saved = courseRepository.save(Course.single(user.getId(),
			new LearningInput("자바", Level.BEGINNER, null, Purpose.WORK, null, null), "코스", report));

		// when
		var actual = courseRepository.findById(saved.getId()).orElseThrow();

		// then
		assertThat(actual).isNotSameAs(saved);
		assertThat(actual.getMasteryReport()).isEqualTo(report);
		String expected = populated ? "{\"shortened\":[\"변수\",\"타입\",\"변수\"],\"revisited\":[\"함수\"]}"
			: "{\"shortened\":[],\"revisited\":[]}";
		assertThat(jdbcTemplate.queryForObject("SELECT mastery_report = ?::jsonb FROM courses WHERE id = ?",
			Boolean.class, expected, actual.getId())).isTrue();
	}

	@Test
	@DisplayName("이 기능 전에 만든 코스의 비어 있는 반영 내역은 읽을 때도 비어 있다")
	void preservesMissingMasteryReportOnRead() {
		// given
		var user = userRepository.save(UserFixtures.user("me@example.com"));
		var saved = courseRepository.save(CourseFixtures.single(user.getId()));
		jdbcTemplate.update("UPDATE courses SET mastery_report = NULL WHERE id = ?", saved.getId());

		// when
		var actual = courseRepository.findById(saved.getId()).orElseThrow();

		// then
		assertThat(actual.getMasteryReport()).isNull();
	}

	@Test
	@DisplayName("로드맵의 코스는 입력 없이 계획의 개념을 한국어와 순서 및 계약의 JSON 키 그대로 저장한다")
	void reloadsRoadmapCourseWithOrderedPlanConcepts() {
		// given
		var user = userRepository.save(UserFixtures.user("me@example.com"));
		var roadmap = roadmapRepository.save(RoadmapFixtures.roadmap(user.getId()));
		var concepts = List.of(new PlannedConcept("변수", "값을 담는 이름"), new PlannedConcept("타입", "값의 종류"));
		var saved = courseRepository.save(CourseFixtures.inRoadmap(roadmap, 2, "자바 문법", "기본 문법을 익혀요", concepts));

		// when
		var actual = courseRepository.findById(saved.getId()).orElseThrow();

		// then
		assertThat(actual).isNotSameAs(saved);
		assertThat(actual.getId()).isPositive();

		assertThat(actual.getUserId()).isEqualTo(roadmap.getUserId());
		assertThat(actual.getRoadmap().getId()).isEqualTo(roadmap.getId());
		assertThat(Hibernate.isInitialized(actual.getRoadmap())).isFalse();
		assertThat(actual.getPosition()).isEqualTo(2);
		assertThat(actual.getTitle()).isEqualTo("자바 문법");
		assertThat(actual.getPlanDescription()).isEqualTo("기본 문법을 익혀요");
		assertThat(actual.getPlanConcepts()).containsExactlyElementsOf(concepts);
		assertThat(actual.getInput()).isNull();

		assertThat(actual.getStatus()).isEqualTo(CourseStatus.NOT_PLANNED);
		assertThat(actual.getMasteryReport()).isNull();

		assertThat(actual.getStartedAt()).isNull();
		assertThat(actual.getLastLessonOpenedAt()).isNull();
		assertThat(actual.getCompletedAt()).isNull();

		// @checkstyle:off — 텍스트 블록의 공백 들여쓰기를 보존한다.
		assertThat(jdbcTemplate.queryForObject("""
			SELECT plan_concepts = '[{"name":"변수","definition":"값을 담는 이름"},
			  {"name":"타입","definition":"값의 종류"}]'::jsonb FROM courses WHERE id = ?
			""", Boolean.class, actual.getId())).isTrue();
		// @checkstyle:on
	}
}
