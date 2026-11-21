package com.mogum.course.service;

import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.CourseStatus;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.domain.RoadmapFixtures;
import com.mogum.course.repository.FakeCourseRepository;
import com.mogum.course.repository.FakeLessonRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CourseReadServiceTest {
    private final FakeCourseRepository courseRepository = new FakeCourseRepository();
    private final FakeLessonRepository lessonRepository = new FakeLessonRepository();
    private final CourseReadService courseReadService = new CourseReadService(courseRepository, lessonRepository);

    @ParameterizedTest(name = "상태={0}")
    @EnumSource(CourseStatus.class)
    @DisplayName("코스 상태에 맞춰 진도와 다음 강의 및 완료 시각을 읽고 바꾸지 않는다")
    void readsCourseWithoutMutation(CourseStatus status) {
        // given
        var course = status == CourseStatus.NOT_PLANNED
                ? CourseFixtures.inRoadmap(RoadmapFixtures.roadmap(1L), 1, "첫", "설명", List.of())
                : CourseFixtures.withStatus(CourseFixtures.single(1L), status);
        courseRepository.save(course);
        if (status != CourseStatus.NOT_PLANNED) {
            lessonRepository.save(LessonFixtures.withCompletedAt(LessonFixtures.lesson(course, 1),
                    Instant.parse("2026-11-18T00:00:00Z")));
            lessonRepository.save(LessonFixtures.lesson(course, 2));
        }

        // when
        var result = courseReadService.read(1L, course.getId());

        // then
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.progress().completedLessons()).isEqualTo(status == CourseStatus.NOT_PLANNED ? 0 : 1);
        assertThat(result.progress().totalLessons()).isEqualTo(status == CourseStatus.NOT_PLANNED ? 0 : 2);
        assertThat(result.nextLessonId()).isEqualTo(status == CourseStatus.LEARNING ? 2L : null);
        assertThat(result.completedAt()).isEqualTo(course.getCompletedAt());
        assertThat(course.getStatus()).isEqualTo(status);
    }

    @ParameterizedTest(name = "남의 것={0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("없는 코스와 남의 코스는 같은 없는 것으로 답한다")
    void hidesForeignAndMissingCourses(boolean foreign) {
        // given
        long id = foreign ? courseRepository.save(CourseFixtures.single(2L)).getId() : 999L;

        // when & then
        assertThatThrownBy(() -> courseReadService.read(1L, id)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("코스가 없으면 객체 안의 빈 목록으로 답한다")
    void returnsEmptyList() {
        // when
        var result = courseReadService.list(1L);

        // then
        assertThat(result.courses()).isEmpty();
    }
}
