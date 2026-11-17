package com.mogum.course.service;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.FakeCreditEntryRepository;
import com.mogum.account.repository.FakeUserRepository;
import com.mogum.account.service.CreditLedger;
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.CourseStatus;
import com.mogum.course.domain.RoadmapFixtures;
import com.mogum.course.repository.FakeCourseJobRepository;
import com.mogum.course.repository.FakeCourseRepository;
import com.mogum.course.repository.FakeLessonRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CourseCommandServiceTest {
    private final FakeCourseRepository courseRepository = new FakeCourseRepository();
    private final FakeCourseJobRepository courseJobRepository = new FakeCourseJobRepository();
    private final FakeUserRepository userRepository = new FakeUserRepository();
    private final FakeLessonRepository lessonRepository = new FakeLessonRepository();
    private final CreditLedger creditLedger = new CreditLedger(userRepository, new FakeCreditEntryRepository());
    private final CourseReadService courseReadService = new CourseReadService(courseRepository, lessonRepository);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-18T00:00:00Z"), ZoneOffset.UTC);
    private final CourseCommandService courseCommandService = new CourseCommandService(
            courseRepository, courseJobRepository, creditLedger, courseReadService, clock);

    @Test
    @DisplayName("검토 중 시작은 학습 중 응답과 시작 시각을 반환한다")
    void startsReviewingCourse() {
        // given
        var user = userRepository.save(UserFixtures.user("me@example.com"));
        var course = courseRepository.save(CourseFixtures.single(user.getId()));

        // when
        var result = courseCommandService.start(user.getId(), course.getId());

        // then
        assertThat(result.status()).isEqualTo(CourseStatus.LEARNING);
        assertThat(course.getStartedAt()).isEqualTo(clock.instant());
    }

    @ParameterizedTest(name = "상태={0}")
    @EnumSource(value = CourseStatus.class, names = {"LEARNING", "COMPLETED"})
    @DisplayName("이미 시작했으면 상태와 원래 시각을 그대로 돌려준다")
    void preservesAlreadyStartedCourse(CourseStatus status) {
        // given
        var user = userRepository.save(UserFixtures.user("me@example.com"));
        var course = courseRepository.save(CourseFixtures.withStatus(CourseFixtures.single(user.getId()), status));
        var startedAt = course.getStartedAt();

        // when
        var result = courseCommandService.start(user.getId(), course.getId());

        // then
        assertThat(result.status()).isEqualTo(status);
        assertThat(course.getStartedAt()).isEqualTo(startedAt);
    }

    @Test
    @DisplayName("끝나지 않은 다시 짜기가 있으면 시작을 거절하고 상태를 보존한다")
    void rejectsUnfinishedReplan() {
        // given
        var user = userRepository.save(UserFixtures.user("me@example.com"));
        var course = courseRepository.save(CourseFixtures.single(user.getId()));
        courseJobRepository.unfinishedReplan = true;

        // when & then
        assertThatThrownBy(() -> courseCommandService.start(user.getId(), course.getId()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ROADMAP_REPLAN_IN_PROGRESS));
        assertThat(course.getStatus()).isEqualTo(CourseStatus.REVIEWING);
        assertThat(course.getStartedAt()).isNull();
    }

    @Test
    @DisplayName("강의가 없는 짜기 전 코스는 시작할 수 없다")
    void rejectsUnplannedCourse() {
        // given
        var user = userRepository.save(UserFixtures.user("me@example.com"));
        var course = courseRepository.save(CourseFixtures.inRoadmap(
                RoadmapFixtures.roadmap(user.getId()), 1, "첫", "설명", List.of()));

        // when & then
        assertThatThrownBy(() -> courseCommandService.start(user.getId(), course.getId()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.COURSE_START_NOT_ALLOWED));
    }
}
