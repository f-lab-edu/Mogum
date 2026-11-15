package com.mogum.job.service;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.course.domain.*;
import com.mogum.course.repository.*;
import com.mogum.job.domain.*;
import com.mogum.job.repository.GenerationJobRepository;
import com.mogum.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class JobReadServiceIntegrationTest {
    @Autowired JobReadService service;
    @Autowired GenerationJobRepository jobs;
    @Autowired UserRepository users;
    @Autowired CourseRepository courses;
    @Autowired RoadmapRepository roadmaps;
    @Autowired LessonRepository lessons;
    @Autowired JdbcTemplate jdbc;

    static Stream<Object[]> typesAndStates() {
        return Arrays.stream(JobType.values()).flatMap(type -> Arrays.stream(JobStatus.values()).map(status -> new Object[]{type, status}));
    }

    @ParameterizedTest(name = "종류={0}, 상태={1}")
    @MethodSource("typesAndStates")
    @DisplayName("여섯 종류의 모든 상태를 요약과 성공 결과 또는 실패 묶음으로 읽고 DB를 보존한다")
    void readsAllTypesAndStates(JobType type, JobStatus status) {
        // given
        long owner = users.save(UserFixtures.approved("me@example.com")).getId();
        var input = new LearningInput("물리", Level.OTHER, "수학은 알아요", Purpose.OTHER, "연구", "비공개 메모");
        var course = courses.save(CourseFixtures.single(owner));
        var roadmap = roadmaps.save(RoadmapFixtures.roadmap(owner));
        var lesson = lessons.save(LessonFixtures.lesson(course, 1));
        var job = jobs.save(switch (type) {
            case NEW_COURSE -> GenerationJobFixtures.newCourse(owner, input);
            case NEW_ROADMAP -> GenerationJobFixtures.newRoadmap(owner, input);
            case PLAN_ROADMAP_COURSE -> GenerationJobFixtures.planRoadmapCourse(owner, course.getId());
            case REPLAN_COURSE -> GenerationJobFixtures.replanCourse(owner, course.getId(), "비공개 피드백");
            case REPLAN_ROADMAP -> GenerationJobFixtures.replanRoadmap(owner, roadmap.getId(), "비공개 피드백");
            case MATERIAL -> GenerationJobFixtures.material(owner, lesson.getId());
        });
        var now = Instant.parse("2026-11-15T00:00:00Z");
        switch (status) {
            case QUEUED -> { }
            case RUNNING -> GenerationJobFixtures.running(jdbc, job.getId(), 3, now, now.plusSeconds(100));
            case FAILED -> GenerationJobFixtures.failed(jdbc, job.getId(), JobErrorType.PROVIDER_REJECTED, now);
            case SUCCEEDED -> GenerationJobFixtures.succeeded(jdbc, job.getId(), switch (type) {
                case NEW_COURSE, PLAN_ROADMAP_COURSE, REPLAN_COURSE -> course.getId();
                default -> null;
            }, switch (type) { case NEW_ROADMAP, REPLAN_ROADMAP -> roadmap.getId(); default -> null; },
                    type == JobType.MATERIAL ? lesson.getId() : null, now);
        }
        var before = jdbc.queryForMap("SELECT * FROM generation_jobs WHERE id = ?", job.getId());

        // when
        var result = service.read(owner, job.getId());

        // then
        assertThat(result.id()).isEqualTo(job.getId());
        assertThat(result.type()).isEqualTo(type);
        assertThat(result.status()).isEqualTo(status);
        if (type == JobType.NEW_COURSE || type == JobType.NEW_ROADMAP) {
            assertThat(result.subject().topic()).isEqualTo("물리");
            assertThat(result.subject().level()).isEqualTo("OTHER");
            assertThat(result.subject().levelOther()).isEqualTo("수학은 알아요");
            assertThat(result.subject().purpose()).isEqualTo("OTHER");
            assertThat(result.subject().purposeOther()).isEqualTo("연구");
            assertThat(result.subject().title()).isNull();
        } else {
            assertThat(result.subject().topic()).isNull();
            assertThat(result.subject().title()).isEqualTo(switch (type) {
                case MATERIAL -> lesson.getTitle();
                case REPLAN_ROADMAP -> roadmap.getTitle();
                default -> course.getTitle();
            });
        }
        if (status == JobStatus.SUCCEEDED) {
            assertThat(result.result().type()).isEqualTo(switch (type) {
                case NEW_ROADMAP, REPLAN_ROADMAP -> "ROADMAP";
                case MATERIAL -> "LESSON";
                default -> "COURSE";
            });
            assertThat(result.result().id()).isEqualTo(switch (type) {
                case NEW_ROADMAP, REPLAN_ROADMAP -> roadmap.getId();
                case MATERIAL -> lesson.getId();
                default -> course.getId();
            });
            assertThat(result.result().courseId()).isEqualTo(type == JobType.MATERIAL ? course.getId() : null);
        } else assertThat(result.result()).isNull();
        if (status == JobStatus.FAILED) {
            assertThat(result.error().code()).isEqualTo("JOB_TEMPORARY_FAILURE");
            assertThat(result.error().message()).isEqualTo("만들지 못했습니다. 다시 시도하거나 버려 주세요");
        } else assertThat(result.error()).isNull();
        assertThat(jdbc.queryForMap("SELECT * FROM generation_jobs WHERE id = ?", job.getId())).isEqualTo(before);
    }

    static Stream<Object[]> typesAndErrors() {
        return Arrays.stream(JobType.values()).flatMap(type -> Arrays.stream(JobErrorType.values())
                .map(error -> new Object[]{type, error}));
    }

    @ParameterizedTest(name = "종류={0}, 실패 까닭={1}")
    @MethodSource("typesAndErrors")
    @DisplayName("실패 까닭 넷을 사용자 행동이 같은 두 묶음으로 반환한다")
    void groupsFailureCauses(JobType type, JobErrorType error) {
        // given
        long owner = users.save(UserFixtures.approved("me@example.com")).getId();
        var input = new LearningInput("자바", Level.BEGINNER, null, Purpose.WORK, null, null);
        var course = courses.save(CourseFixtures.single(owner));
        var roadmap = roadmaps.save(RoadmapFixtures.roadmap(owner));
        var lesson = lessons.save(LessonFixtures.lesson(course, 1));
        var job = jobs.save(switch (type) {
            case NEW_COURSE -> GenerationJobFixtures.newCourse(owner, input);
            case NEW_ROADMAP -> GenerationJobFixtures.newRoadmap(owner, input);
            case PLAN_ROADMAP_COURSE -> GenerationJobFixtures.planRoadmapCourse(owner, course.getId());
            case REPLAN_COURSE -> GenerationJobFixtures.replanCourse(owner, course.getId(), null);
            case REPLAN_ROADMAP -> GenerationJobFixtures.replanRoadmap(owner, roadmap.getId(), null);
            case MATERIAL -> GenerationJobFixtures.material(owner, lesson.getId());
        });
        GenerationJobFixtures.failed(jdbc, job.getId(), error, Instant.parse("2026-11-15T00:00:00Z"));

        // when
        var result = service.read(owner, job.getId());

        // then
        boolean inputProblem = error == JobErrorType.INPUT_TOO_LARGE;
        assertThat(result.error().code()).isEqualTo(inputProblem ? "JOB_INPUT_FAILURE" : "JOB_TEMPORARY_FAILURE");
        assertThat(result.error().message()).isEqualTo(inputProblem ? "주제를 좁혀 새로 만들어 주세요" : "만들지 못했습니다. 다시 시도하거나 버려 주세요");
        assertThat(result.error().fields()).isNull();
        assertThat(result.error().retryAfterSeconds()).isNull();
    }

    @org.junit.jupiter.api.Test
    @DisplayName("남의 작업과 없는 작업은 같은 오류로 반환한다")
    void rejectsForeignAndMissingJobs() {
        // given
        long owner = users.save(UserFixtures.approved("owner@example.com")).getId();
        var job = jobs.save(GenerationJobFixtures.newCourse(owner,
                new LearningInput("비공개 주제", Level.BEGINNER, null, Purpose.WORK, null, null)));

        // when & then
        assertThatThrownBy(() -> service.read(owner + 1, job.getId())).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.read(owner, 999L)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }
}
