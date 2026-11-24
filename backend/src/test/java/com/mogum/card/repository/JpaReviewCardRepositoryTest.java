package com.mogum.card.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.repository.CourseRepository;
import com.mogum.support.RepositoryTest;

import jakarta.persistence.EntityManager;

@RepositoryTest
class JpaReviewCardRepositoryTest {
	@Autowired
	EntityManager entityManager;
	@Autowired
	CourseRepository courseRepository;
	@Autowired
	UserRepository userRepository;
	@Autowired
	PlatformTransactionManager platformTransactionManager;

	@ParameterizedTest(name = "대상={0}")
	@ValueSource(strings = {"owned", "foreign", "missing"})
	@DisplayName("코스 제목은 주인을 확인한 네이티브 읽기로만 가져온다")
	void readsOnlyOwnedCourseTitle(String scenario) {
		// given
		var user = userRepository.save(UserFixtures.user("me@example.com"));
		var course = courseRepository.save(CourseFixtures.single(user.getId()));
		var transactionTemplate = new TransactionTemplate(platformTransactionManager);
		var reviewCardRepository = new JpaReviewCardRepository(entityManager);
		long userId = scenario.equals("foreign") ? user.getId() + 1 : user.getId();
		long courseId = scenario.equals("missing") ? Long.MAX_VALUE : course.getId();
		// when
		var title = transactionTemplate.execute(tx -> reviewCardRepository.findOwnedCourseTitle(userId, courseId));
		// then
		if (scenario.equals("owned")) {
			assertThat(title).contains(course.getTitle());
		} else {
			assertThat(title).isEmpty();
		}
	}
}
