package com.mogum.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import com.mogum.generation.material.MaterialInput;
import com.mogum.support.IntegrationTest;

@IntegrationTest
class MaterialReaderIntegrationTest {
	@Autowired
	MaterialReader materialReader;
	@Autowired
	UserRepository userRepository;
	@Autowired
	CourseRepository courseRepository;
	@Autowired
	LessonRepository lessonRepository;
	@Autowired
	RoadmapRepository roadmapRepository;

	@ParameterizedTest(name = "로드맵={0}")
	@ValueSource(booleans = {false, true})
	@DisplayName("단일 코스와 로드맵의 입력에서 허용된 재료만 값으로 복사한다")
	void copiesOnlyMaterialInput(boolean inRoadmap) {
		// given
		long userId = userRepository.save(UserFixtures.user("me@example.com")).getId();
		var input = new LearningInput("주제", Level.OTHER, "넣지 않을 수준", Purpose.OTHER, "목적", "메모");
		var roadmap = inRoadmap ? roadmapRepository.save(RoadmapFixtures.roadmap(userId, input, "로드맵", "설명")) : null;
		var course = courseRepository.save(inRoadmap
			? CourseFixtures.inRoadmap(roadmap, 1, "코스", "계획 설명", List.of(new PlannedConcept("계획개념", "계획정의")))
			: CourseFixtures.single(userId, input, "코스"));
		var concepts = List.of(new PlannedConcept("이번 개념", "이번 정의"));
		var target = lessonRepository.save(LessonFixtures.lesson(course, 2, "이번", "이번 설명", concepts));
		lessonRepository
			.save(LessonFixtures.lesson(course, 3, "뒤", "뒤 설명", List.of(new PlannedConcept("비밀", "비밀 정의"))));
		lessonRepository.save(LessonFixtures.lesson(course, 1, "앞", "앞 설명", List.of()));
		// when
		var result = materialReader.read(target.getId());
		// then
		assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
		assertThat(result.courseTitle()).isEqualTo("코스");
		assertThat(result.topic()).isEqualTo("주제");
		assertThat(result.purpose()).isEqualTo(Purpose.OTHER);
		assertThat(result.purposeOther()).isEqualTo("목적");
		assertThat(result.memo()).isEqualTo("메모");
		assertThat(result.lesson()).isEqualTo(new MaterialInput.LessonLine(2, "이번", "이번 설명", concepts));
		assertThat(result.lessons()).containsExactly(new MaterialInput.LessonSummary(1, "앞", "앞 설명", false),
			new MaterialInput.LessonSummary(2, "이번", "이번 설명", true),
			new MaterialInput.LessonSummary(3, "뒤", "뒤 설명", false));
	}
}
