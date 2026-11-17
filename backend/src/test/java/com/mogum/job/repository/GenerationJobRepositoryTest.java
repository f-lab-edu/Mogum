package com.mogum.job.repository;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.LearningInput;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.domain.Level;
import com.mogum.course.domain.Purpose;
import com.mogum.course.domain.RoadmapFixtures;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.course.repository.RoadmapRepository;
import com.mogum.job.domain.GenerationJobFixtures;
import com.mogum.job.domain.JobErrorType;
import com.mogum.job.domain.JobStatus;
import com.mogum.job.domain.JobType;
import com.mogum.support.RepositoryTest;
import com.mogum.support.TestClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@RepositoryTest
class GenerationJobRepositoryTest {
    @Autowired private GenerationJobRepository generationJobRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoadmapRepository roadmapRepository;
    @Autowired private CourseRepository courseRepository;
    @Autowired private LessonRepository lessonRepository;
    @Autowired private TestClock testClock;
    @Autowired private JdbcTemplate jdbcTemplate;

    @ParameterizedTest(name = "종류 {0}")
    @EnumSource(JobType.class)
    @DisplayName("여섯 종류의 입력과 대상 및 공용 시계의 시각을 저장하고 다시 읽는다")
    void reloadsEveryFactoryWithInputTargetsAndAuditing(JobType type) {
        // given
        var user = userRepository.save(UserFixtures.user("owner@example.com"));
        var roadmap = roadmapRepository.save(RoadmapFixtures.roadmap(user.getId()));
        var course = courseRepository.save(CourseFixtures.single(user.getId()));
        var lesson = lessonRepository.save(LessonFixtures.lesson(course, 1));
        var input = new LearningInput("자바", Level.OTHER, "실습 경험", Purpose.OTHER, "개인 프로젝트", "예제 중심");

        var instant = Instant.parse("2026-11-15T01:23:45.123456Z");
        testClock.set(instant);

        var job = switch (type) {
            case NEW_COURSE -> GenerationJobFixtures.newCourse(user.getId(), input);
            case NEW_ROADMAP -> GenerationJobFixtures.newRoadmap(user.getId(), input);
            case PLAN_ROADMAP_COURSE -> GenerationJobFixtures.planRoadmapCourse(user.getId(), course.getId());
            case REPLAN_COURSE -> GenerationJobFixtures.replanCourse(user.getId(), course.getId(), "예제를 늘려 주세요");
            case REPLAN_ROADMAP -> GenerationJobFixtures.replanRoadmap(user.getId(), roadmap.getId(), "순서를 바꿔 주세요");
            case MATERIAL -> GenerationJobFixtures.material(user.getId(), lesson.getId());
        };
        var saved = generationJobRepository.save(job);

        // when
        var actual = generationJobRepository.findById(saved.getId()).orElseThrow();

        // then
        assertThat(actual).isNotSameAs(saved);
        assertThat(actual.getId()).isPositive();
        assertThat(actual.getUserId()).isEqualTo(user.getId());
        assertThat(actual.getType()).isEqualTo(type);
        assertThat(actual.getStatus()).isEqualTo(JobStatus.QUEUED);

        assertThat(actual.getInput()).usingRecursiveComparison().isEqualTo(
                type == JobType.NEW_COURSE || type == JobType.NEW_ROADMAP ? input : null);
        assertThat(actual.getFeedback()).isEqualTo(switch (type) {
            case REPLAN_COURSE -> "예제를 늘려 주세요";
            case REPLAN_ROADMAP -> "순서를 바꿔 주세요";
            default -> null;
        });
        assertThat(actual.getCourseId()).isEqualTo(
                type == JobType.PLAN_ROADMAP_COURSE || type == JobType.REPLAN_COURSE ? course.getId() : null);
        assertThat(actual.getRoadmapId()).isEqualTo(type == JobType.REPLAN_ROADMAP ? roadmap.getId() : null);
        assertThat(actual.getLessonId()).isEqualTo(type == JobType.MATERIAL ? lesson.getId() : null);

        assertThat(actual.getClaimCount()).isZero();
        assertThat(actual.getLeaseUntil()).isNull();
        assertThat(actual.getErrorType()).isNull();
        assertThat(actual.getStartedAt()).isNull();
        assertThat(actual.getFinishedAt()).isNull();

        assertThat(actual.getCreatedAt()).isEqualTo(instant);
        assertThat(actual.getUpdatedAt()).isEqualTo(instant);
    }

    @ParameterizedTest(name = "상태 {0}")
    @EnumSource(JobStatus.class)
    @DisplayName("SQL 픽스처로 바꾼 상태와 시한 및 마지막 시도 시각을 다시 읽는다")
    void reloadsWorkerFieldsAndSqlUpdatedAt(JobStatus status) {
        // given
        long user = userRepository.save(UserFixtures.user("owner@example.com")).getId();
        long course = courseRepository.save(CourseFixtures.single(user)).getId();
        var saved = generationJobRepository.save(GenerationJobFixtures.replanCourse(user, course, null));
        var startedAt = testClock.instant().plusSeconds(10);
        var leaseUntil = startedAt.plusSeconds(300);
        var finishedAt = startedAt.plusSeconds(60);

        if (status != JobStatus.QUEUED) {
            GenerationJobFixtures.running(jdbcTemplate, saved.getId(), 2, startedAt, leaseUntil);
        }
        if (status == JobStatus.FAILED) {
            GenerationJobFixtures.failed(jdbcTemplate, saved.getId(), JobErrorType.INVALID_OUTPUT, finishedAt);
        } else if (status == JobStatus.SUCCEEDED) {
            GenerationJobFixtures.succeeded(jdbcTemplate, saved.getId(), course, null, null, finishedAt);
        }

        // when
        var actual = generationJobRepository.findById(saved.getId()).orElseThrow();

        // then
        assertThat(actual.getStatus()).isEqualTo(status);

        assertThat(actual.getClaimCount()).isEqualTo(status == JobStatus.QUEUED ? 0 : 2);
        assertThat(actual.getStartedAt()).isEqualTo(status == JobStatus.QUEUED ? null : startedAt);
        assertThat(actual.getLeaseUntil()).isEqualTo(status == JobStatus.RUNNING ? leaseUntil : null);
        assertThat(actual.getErrorType()).isEqualTo(status == JobStatus.FAILED ? JobErrorType.INVALID_OUTPUT : null);
        assertThat(actual.getFinishedAt()).isEqualTo(
                status == JobStatus.SUCCEEDED || status == JobStatus.FAILED ? finishedAt : null);

        assertThat(actual.getCreatedAt()).isEqualTo(testClock.instant());
        assertThat(actual.getUpdatedAt()).isEqualTo(switch (status) {
            case QUEUED -> testClock.instant();
            case RUNNING -> startedAt;
            case SUCCEEDED, FAILED -> finishedAt;
        });
    }
}
