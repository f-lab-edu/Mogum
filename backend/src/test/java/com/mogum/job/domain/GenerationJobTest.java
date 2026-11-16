package com.mogum.job.domain;

import com.mogum.course.domain.LearningInput;
import com.mogum.course.domain.Level;
import com.mogum.course.domain.Purpose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GenerationJobTest {

    @Test
    @DisplayName("새 코스 작업은 입력을 담고 대상 없이 기다림으로 시작한다")
    void createsNewCourse() {
        // given
        var input = new LearningInput("자바", Level.OTHER, "실습 경험", Purpose.OTHER, "개인 프로젝트", "예제 중심");

        // when
        var job = GenerationJob.newCourse(17L, input);

        // then
        assertQueued(job, JobType.NEW_COURSE, input, null, null, null, null);
    }

    @Test
    @DisplayName("새 로드맵 작업은 입력을 담고 대상 없이 기다림으로 시작한다")
    void createsNewRoadmap() {
        // given
        var input = new LearningInput("자바", Level.OTHER, "실습 경험", Purpose.OTHER, "개인 프로젝트", "예제 중심");

        // when
        var job = GenerationJob.newRoadmap(17L, input);

        // then
        assertQueued(job, JobType.NEW_ROADMAP, input, null, null, null, null);
    }

    @Test
    @DisplayName("로드맵 코스의 강의 목록 작업은 코스만 가리킨다")
    void createsPlanRoadmapCourse() {
        // given
        long targetId = 91L;

        // when
        var job = GenerationJob.planRoadmapCourse(17L, targetId);

        // then
        assertQueued(job, JobType.PLAN_ROADMAP_COURSE, null, null, 91L, null, null);
    }

    @Test
    @DisplayName("코스 다시 짜기는 코스와 피드백만 담는다")
    void createsReplanCourse() {
        // given
        long targetId = 91L;

        // when
        var job = GenerationJob.replanCourse(17L, targetId, "예제를 늘려 주세요");

        // then
        assertQueued(job, JobType.REPLAN_COURSE, null, "예제를 늘려 주세요", 91L, null, null);
    }

    @Test
    @DisplayName("로드맵 다시 짜기는 로드맵과 피드백만 담는다")
    void createsReplanRoadmap() {
        // given
        long targetId = 91L;

        // when
        var job = GenerationJob.replanRoadmap(17L, targetId, "예제를 늘려 주세요");

        // then
        assertQueued(job, JobType.REPLAN_ROADMAP, null, "예제를 늘려 주세요", null, 91L, null);
    }

    @Test
    @DisplayName("강의 자료 작업은 강의만 가리킨다")
    void createsMaterial() {
        // given
        long targetId = 91L;

        // when
        var job = GenerationJob.material(17L, targetId);

        // then
        assertQueued(job, JobType.MATERIAL, null, null, null, null, 91L);
    }

    private void assertQueued(GenerationJob job, JobType type, LearningInput input, String feedback,
                              Long courseId, Long roadmapId, Long lessonId) {
        assertThat(job.getId()).isNull();
        assertThat(job.getCreatedAt()).isNull();
        assertThat(job.getUpdatedAt()).isNull();
        assertThat(job.getUserId()).isEqualTo(17L);
        assertThat(job.getType()).isEqualTo(type);
        assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.getInput()).isSameAs(input);
        assertThat(job.getFeedback()).isEqualTo(feedback);
        assertThat(job.getCourseId()).isEqualTo(courseId);
        assertThat(job.getRoadmapId()).isEqualTo(roadmapId);
        assertThat(job.getLessonId()).isEqualTo(lessonId);
        assertThat(job.getClaimCount()).isZero();
        assertThat(job.getLeaseUntil()).isNull();
        assertThat(job.getErrorType()).isNull();
        assertThat(job.getStartedAt()).isNull();
        assertThat(job.getFinishedAt()).isNull();
    }
}
