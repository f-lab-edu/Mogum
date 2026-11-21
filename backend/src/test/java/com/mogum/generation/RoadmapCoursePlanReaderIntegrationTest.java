package com.mogum.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.LearningInput;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.domain.Level;
import com.mogum.course.domain.PlannedConcept;
import com.mogum.course.domain.Purpose;
import com.mogum.course.domain.RoadmapFixtures;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.course.repository.RoadmapRepository;
import com.mogum.generation.plan.CourseOutline;
import com.mogum.generation.plan.LessonOutline;
import com.mogum.support.IntegrationTest;

@IntegrationTest
class RoadmapCoursePlanReaderIntegrationTest {
	@Autowired
	RoadmapCoursePlanReader roadmapCoursePlanReader;
	@Autowired
	UserRepository userRepository;
	@Autowired
	CourseRepository courseRepository;
	@Autowired
	RoadmapRepository roadmapRepository;
	@Autowired
	LessonRepository lessonRepository;

	@Test
	@DisplayName("읽기 트랜잭션이 끝난 뒤 엔티티 없는 값으로 바로 앞 실제 목록과 뒤 계획을 순서대로 쓸 수 있다")
	void copiesMaterialsBeforeClosingTransaction() {
		// given
		var user = userRepository.save(UserFixtures.user("me@example.com"));
		var roadmap = roadmapRepository.save(RoadmapFixtures.roadmap(user.getId(),
			new LearningInput("주제", Level.OTHER, "수준", Purpose.OTHER, "목적", "메모"), "로드맵", "설명"));
		var concepts = List.of(new PlannedConcept("계획 개념", "계획 정의"));
		var fifth = courseRepository.save(CourseFixtures.inRoadmap(roadmap, 5, "다섯째", "다섯 설명", concepts));
		var fourth = courseRepository.save(CourseFixtures.inRoadmap(roadmap, 4, "넷째", "넷 설명", concepts));
		var third = courseRepository.save(CourseFixtures.inRoadmap(roadmap, 3, "셋째", "셋 설명", concepts));
		var second = courseRepository.save(CourseFixtures.inRoadmap(roadmap, 2, "둘째", "둘 설명", concepts));
		var first = courseRepository.save(CourseFixtures.inRoadmap(roadmap, 1, "첫째", "첫 설명", concepts));
		var actual = List.of(new PlannedConcept("실제 개념", "실제 정의"));
		lessonRepository.save(LessonFixtures.lesson(second, 2, "뒤 강의", "뒤 설명", actual));
		lessonRepository.save(LessonFixtures.lesson(second, 1, "앞 강의", "앞 설명", actual));
		lessonRepository.save(LessonFixtures.lesson(first, 1, "넣지 않을 강의", "넣지 않을 설명", actual));

		// when
		var input = roadmapCoursePlanReader.read(third.getId());

		// then
		assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
		assertThat(input.input().topic()).isEqualTo("주제");
		assertThat(input.input().purpose()).isEqualTo(Purpose.OTHER);
		assertThat(input.input().purposeOther()).isEqualTo("목적");
		assertThat(input.input().memo()).isEqualTo("메모");
		assertThat(input.input().level()).isNull();
		assertThat(input.input().levelOther()).isNull();
		assertThat(input.context().position()).isEqualTo(3);
		assertThat(input.context().coursePlan()).isEqualTo(new CourseOutline("셋째", "셋 설명", concepts));
		assertThat(input.context().previousCourseLessons()).containsExactly(
			new LessonOutline("앞 강의", "앞 설명", actual), new LessonOutline("뒤 강의", "뒤 설명", actual));
		assertThat(input.context().followingCoursePlans()).containsExactly(
			new CourseOutline(fourth.getTitle(), "넷 설명", concepts),
			new CourseOutline(fifth.getTitle(), "다섯 설명", concepts));
	}
}
