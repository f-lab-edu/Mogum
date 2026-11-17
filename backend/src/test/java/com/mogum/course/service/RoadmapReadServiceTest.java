package com.mogum.course.service;

import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.CourseStatus;
import com.mogum.course.domain.RoadmapFixtures;
import com.mogum.course.domain.RoadmapStatus;
import com.mogum.course.dto.CourseProgress;
import com.mogum.course.repository.CourseJobRepository.PlanningJob;
import com.mogum.course.repository.FakeCourseJobRepository;
import com.mogum.course.repository.FakeCourseRepository;
import com.mogum.course.repository.FakeRoadmapRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoadmapReadServiceTest {
    private final FakeRoadmapRepository roadmapRepository = new FakeRoadmapRepository();
    private final FakeCourseRepository courseRepository = new FakeCourseRepository();
    private final FakeCourseJobRepository courseJobRepository = new FakeCourseJobRepository();
    private final RoadmapReadService roadmapReadService = new RoadmapReadService(
            roadmapRepository, courseRepository, courseJobRepository);

    @ParameterizedTest(name = "앞 코스={0}, 누르기={1}")
    @CsvSource({"REVIEWING,false", "LEARNING,false", "COMPLETED,true"})
    @DisplayName("다음 코스는 순서가 가장 앞선 작업 없는 짜기 전 코스이고 앞 코스가 다 마쳐야 누른다")
    void choosesNextCourse(CourseStatus previousStatus, boolean enabled) {
        // given
        var roadmap = roadmapRepository.save(RoadmapFixtures.withStatus(
                RoadmapFixtures.roadmap(1L), RoadmapStatus.IN_PROGRESS));
        var second = courseRepository.save(CourseFixtures.inRoadmap(roadmap, 2, "둘", "설명", List.of()));
        courseRepository.save(CourseFixtures.withStatus(
                CourseFixtures.inRoadmap(roadmap, 1, "하나", "설명", List.of()), previousStatus));
        courseRepository.save(CourseFixtures.inRoadmap(roadmap, 3, "셋", "설명", List.of()));

        // when
        var result = roadmapReadService.read(1L, roadmap.getId());

        // then
        assertThat(result.nextCourse().courseId()).isEqualTo(second.getId());
        assertThat(result.nextCourse().enabled()).isEqualTo(enabled);
        assertThat(result.courses()).extracting(item -> item.position()).containsExactly(1, 2, 3);
    }

    @ParameterizedTest(name = "실패={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("짜기 전 코스의 작업은 만드는 중 또는 실패와 작업 ID로 보이고 다음 코스에서 빠진다")
    void showsUnfinishedJob(boolean failed) {
        // given
        var roadmap = roadmapRepository.save(RoadmapFixtures.withStatus(
                RoadmapFixtures.roadmap(1L), RoadmapStatus.IN_PROGRESS));
        var first = courseRepository.save(CourseFixtures.inRoadmap(roadmap, 1, "첫", "설명", List.of()));
        var second = courseRepository.save(CourseFixtures.inRoadmap(roadmap, 2, "둘", "설명", List.of()));
        courseJobRepository.planningJobs.add(new PlanningJob(first.getId(), 19L, failed));

        // when
        var result = roadmapReadService.read(1L, roadmap.getId());

        // then
        assertThat(result.courses().getFirst().progress())
                .isEqualTo(failed ? CourseProgress.FAILED : CourseProgress.GENERATING);
        assertThat(result.courses().getFirst().jobId()).isEqualTo(19L);
        assertThat(result.nextCourse().courseId()).isEqualTo(second.getId());
        assertThat(result.nextCourse().enabled()).isFalse();
    }

    @ParameterizedTest(name = "로드맵={0}")
    @EnumSource(value = RoadmapStatus.class, names = {"REVIEWING", "COMPLETED"})
    @DisplayName("검토 중과 다 마친 로드맵에는 다음 코스가 없다")
    void omitsNextCourseOutsideProgress(RoadmapStatus status) {
        // given
        var roadmap = roadmapRepository.save(RoadmapFixtures.withStatus(RoadmapFixtures.roadmap(1L), status));
        courseRepository.save(CourseFixtures.inRoadmap(roadmap, 1, "첫", "설명", List.of()));

        // when
        var result = roadmapReadService.read(1L, roadmap.getId());

        // then
        assertThat(result.nextCourse()).isNull();
    }

    @ParameterizedTest(name = "코스={0}")
    @EnumSource(value = CourseStatus.class, names = {"REVIEWING", "LEARNING", "COMPLETED"})
    @DisplayName("강의 목록이 있는 코스는 만들어짐이며 마지막까지 만들면 다음 코스가 없다")
    void showsGeneratedCourse(CourseStatus status) {
        // given
        var roadmap = roadmapRepository.save(RoadmapFixtures.withStatus(
                RoadmapFixtures.roadmap(1L), RoadmapStatus.IN_PROGRESS));
        courseRepository.save(CourseFixtures.withStatus(
                CourseFixtures.inRoadmap(roadmap, 1, "첫", "설명", List.of()), status));

        // when
        var result = roadmapReadService.read(1L, roadmap.getId());

        // then
        assertThat(result.courses().getFirst().progress()).isEqualTo(CourseProgress.GENERATED);
        assertThat(result.courses().getFirst().jobId()).isNull();
        assertThat(result.nextCourse()).isNull();
    }

    @ParameterizedTest(name = "다른 주인={0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("남의 로드맵과 없는 로드맵은 같은 없는 것으로 답한다")
    void hidesForeignAndMissingRoadmaps(boolean foreign) {
        // given
        long id = foreign ? roadmapRepository.save(RoadmapFixtures.roadmap(2L)).getId() : 999L;

        // when & then
        assertThatThrownBy(() -> roadmapReadService.read(1L, id)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }
}
