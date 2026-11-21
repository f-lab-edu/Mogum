package com.mogum.course.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.hibernate.Hibernate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.domain.MaterialFixtures;
import com.mogum.support.RepositoryTest;

@RepositoryTest
class MaterialRepositoryTest {
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private CourseRepository courseRepository;
	@Autowired
	private LessonRepository lessonRepository;
	@Autowired
	private MaterialRepository materialRepository;

	@Test
	@DisplayName("강의 자료는 강의 연결과 한국어 마크다운 본문을 저장한다")
	void reloadsMaterialWithLessonAndMarkdown() {
		// given
		var user = userRepository.save(UserFixtures.user("me@example.com"));
		var course = courseRepository.save(CourseFixtures.single(user.getId()));
		var lesson = lessonRepository.save(LessonFixtures.lesson(course, 1));
		var saved = materialRepository.save(MaterialFixtures.material(lesson, "# 변수\n값을 담는 이름이에요."));

		// when
		var actual = materialRepository.findById(saved.getId()).orElseThrow();

		// then
		assertThat(actual).isNotSameAs(saved);
		assertThat(actual.getId()).isPositive();
		assertThat(actual.getLesson().getId()).isEqualTo(lesson.getId());
		assertThat(Hibernate.isInitialized(actual.getLesson())).isFalse();
		assertThat(actual.getBody()).isEqualTo("# 변수\n값을 담는 이름이에요.");
	}
}
