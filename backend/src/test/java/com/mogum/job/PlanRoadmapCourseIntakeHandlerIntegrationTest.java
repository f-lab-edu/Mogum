package com.mogum.job;

import com.mogum.account.domain.CreditEntry;
import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.CreditEntryRepository;
import com.mogum.account.repository.UserRepository;
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.config.CreditProperties;
import com.mogum.config.JobProperties;
import com.mogum.course.domain.Course;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.CourseStatus;
import com.mogum.course.domain.Roadmap;
import com.mogum.course.domain.RoadmapFixtures;
import com.mogum.course.domain.RoadmapStatus;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.RoadmapRepository;
import com.mogum.job.domain.GenerationJobFixtures;
import com.mogum.job.domain.JobErrorType;
import com.mogum.job.domain.JobStatus;
import com.mogum.job.domain.JobType;
import com.mogum.job.repository.GenerationJobRepository;
import com.mogum.job.service.JobIntakeService;
import com.mogum.job.service.JobResult;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestClock;
import com.mogum.support.TestRequests;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class PlanRoadmapCourseIntakeHandlerIntegrationTest {
    @Autowired JobIntakeService jobIntakeService;
    @Autowired UserRepository userRepository;
    @Autowired CreditEntryRepository creditEntryRepository;
    @Autowired CourseRepository courseRepository;
    @Autowired RoadmapRepository roadmapRepository;
    @Autowired GenerationJobRepository generationJobRepository;
    @Autowired CreditProperties creditProperties;
    @Autowired JobProperties jobProperties;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TestClock testClock;
    @Autowired TestRequests testRequests;
    @Autowired PlatformTransactionManager platformTransactionManager;

    @Test
    @DisplayName("첫 코스 HTTP 받기는 로드맵을 진행 중으로 바꾸고 기다림 작업과 크레딧 사용을 함께 커밋한다")
    void startsRoadmapOnHttpIntake() {
        // given
        long userId = user(100);
        var roadmap = roadmap(userId, RoadmapStatus.REVIEWING);
        var course = course(roadmap, 1, CourseStatus.NOT_PLANNED);
        var client = testRequests.asUser(userId);
        testClock.advance(Duration.ofSeconds(1));

        // when
        var response = client.post().uri("/api/jobs")
                .body(Map.of("type", "PLAN_ROADMAP_COURSE", "courseId", course.getId())).exchange();

        // then
        response.expectStatus().isOk().expectBody().jsonPath("$.data.type").isEqualTo("PLAN_ROADMAP_COURSE")
                .jsonPath("$.data.status").isEqualTo("QUEUED")
                .jsonPath("$.data.subject.title").isEqualTo("계획 1");
        assertThat(roadmapRepository.findById(roadmap.getId()).orElseThrow().getStatus())
                .isEqualTo(RoadmapStatus.IN_PROGRESS);
        assertThat(roadmapRepository.findById(roadmap.getId()).orElseThrow().getUpdatedAt())
                .isEqualTo(testClock.instant());
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM generation_jobs"))
                .containsEntry("status", "QUEUED").containsEntry("course_id", course.getId());
        assertThat(balance(userId)).isEqualTo(100 - creditProperties.planRoadmapCourse());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM credit_entries WHERE type = 'CONSUME'",
                Integer.class)).isEqualTo(1);
        assertThat(courseRepository.findById(course.getId()).orElseThrow().getStatus())
                .isEqualTo(CourseStatus.NOT_PLANNED);
    }

    @ParameterizedTest(name = "거절={0}")
    @EnumSource(value = ErrorCode.class, names = {"CREDIT_INSUFFICIENT", "TOO_MANY_IN_PROGRESS"})
    @DisplayName("처리기 확인 뒤 크레딧이나 건 수에서 거절되면 로드맵과 작업과 크레딧이 모두 돌아간다")
    void rollsBackStartWhenIntakeFails(ErrorCode error) {
        // given
        long userId = user(error == ErrorCode.CREDIT_INSUFFICIENT ? 0 : 100);
        var roadmap = roadmap(userId, RoadmapStatus.REVIEWING);
        var course = course(roadmap, 1, CourseStatus.NOT_PLANNED);
        if (error == ErrorCode.TOO_MANY_IN_PROGRESS) {
            for (int i = 0; i < jobProperties.maxInProgressPerUser(); i++) {
                generationJobRepository.save(GenerationJobFixtures.newCourse(userId,
                        RoadmapFixtures.roadmap(userId).getInput()));
            }
        }
        var before = state();

        // when & then
        assertRejected(userId, course.getId(), error);
        assertThat(state()).isEqualTo(before);
    }

    @ParameterizedTest(name = "다시 짜기={0}")
    @EnumSource(value = JobStatus.class, names = {"QUEUED", "RUNNING", "FAILED"})
    @DisplayName("기다림과 만드는 중과 실패한 로드맵 다시 짜기가 있으면 아무것도 바꾸지 않는다")
    void rejectsUnfinishedReplanWithoutChanges(JobStatus status) {
        // given
        long userId = user(100);
        var roadmap = roadmap(userId, RoadmapStatus.REVIEWING);
        var course = course(roadmap, 1, CourseStatus.NOT_PLANNED);
        var replan = generationJobRepository.save(GenerationJobFixtures.replanRoadmap(userId, roadmap.getId(), null));
        if (status == JobStatus.RUNNING) {
            GenerationJobFixtures.running(jdbcTemplate, replan.getId(), 1, testClock.instant(),
                    testClock.instant().plusSeconds(360));
        } else if (status == JobStatus.FAILED) {
            GenerationJobFixtures.failed(jdbcTemplate, replan.getId(), JobErrorType.INVALID_OUTPUT,
                    testClock.instant());
        }
        var before = state();

        // when & then
        assertRejected(userId, course.getId(), ErrorCode.REPLAN_IN_PROGRESS);
        assertThat(state()).isEqualTo(before);
    }

    @ParameterizedTest(name = "앞 코스={0}, 받을 수 있음={1}")
    @CsvSource({"LEARNING,false", "COMPLETED,true"})
    @DisplayName("다음 코스는 앞 코스 다 마침만 요구하고 카드 복습은 요구하지 않는다")
    void requiresCompletedPreviousCourse(CourseStatus status, boolean accepted) {
        // given
        long userId = user(100);
        var roadmap = roadmap(userId, RoadmapStatus.IN_PROGRESS);
        course(roadmap, 1, status);
        var next = course(roadmap, 2, CourseStatus.NOT_PLANNED);
        var before = state();

        // when & then
        if (accepted) {
            var result = jobIntakeService.receive(userId, request(next.getId()));
            assertThat(result.status()).isEqualTo(JobStatus.QUEUED);
            assertThat(balance(userId)).isEqualTo(100 - creditProperties.planRoadmapCourse());
        } else {
            assertRejected(userId, next.getId(), ErrorCode.PREVIOUS_COURSE_NOT_COMPLETED);
            assertThat(state()).isEqualTo(before);
        }
    }

    @Test
    @DisplayName("가장 앞선 짜기 전 코스가 아니면 받지 않고 아무것도 바꾸지 않는다")
    void rejectsSkippingCourse() {
        // given
        long userId = user(100);
        var roadmap = roadmap(userId, RoadmapStatus.IN_PROGRESS);
        course(roadmap, 1, CourseStatus.NOT_PLANNED);
        course(roadmap, 2, CourseStatus.COMPLETED);
        var third = course(roadmap, 3, CourseStatus.NOT_PLANNED);
        var before = state();

        // when & then
        assertRejected(userId, third.getId(), ErrorCode.PREVIOUS_COURSE_NOT_COMPLETED);
        assertThat(state()).isEqualTo(before);
    }

    @Test
    @DisplayName("이미 검토 중인 코스는 다시 받지 않는다")
    void rejectsAlreadyPlannedCourse() {
        // given
        long userId = user(100);
        var course = course(roadmap(userId, RoadmapStatus.IN_PROGRESS), 1, CourseStatus.REVIEWING);
        var before = state();

        // when & then
        assertRejected(userId, course.getId(), ErrorCode.COURSE_PLAN_NOT_ALLOWED);
        assertThat(state()).isEqualTo(before);
    }

    @Test
    @DisplayName("실패한 첫 코스 작업을 버린 뒤 진행 중 로드맵에서 다시 받을 수 있다")
    void acceptsFirstCourseAfterDiscard() {
        // given
        long userId = user(100);
        var course = course(roadmap(userId, RoadmapStatus.REVIEWING), 1, CourseStatus.NOT_PLANNED);
        var first = jobIntakeService.receive(userId, request(course.getId()));
        GenerationJobFixtures.failed(jdbcTemplate, first.id(), JobErrorType.INVALID_OUTPUT, testClock.instant());
        jobIntakeService.discard(userId, first.id());

        // when
        var second = jobIntakeService.receive(userId, request(course.getId()));

        // then
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(balance(userId)).isEqualTo(100 - creditProperties.planRoadmapCourse());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM generation_jobs", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 첫 코스의 두 동시 받기는 작업과 크레딧 사용 하나로 같은 ID를 반환한다")
    void deduplicatesConcurrentIntake() throws Exception {
        // given
        long userId = user(100);
        var course = course(roadmap(userId, RoadmapStatus.REVIEWING), 1, CourseStatus.NOT_PLANNED);
        var entered = new CountDownLatch(2);
        var executor = Executors.newFixedThreadPool(2);
        var transactionTemplate = new TransactionTemplate(platformTransactionManager);
        try {
            // when — 실제 사용자 행을 먼저 잠가 두 요청을 잠금 자리에서 경합시킨다
            var futures = transactionTemplate.execute(tx -> {
                jdbcTemplate.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, userId);
                Callable<JobResult> receive = () -> {
                    entered.countDown();
                    return jobIntakeService.receive(userId, request(course.getId()));
                };
                var first = executor.submit(receive);
                var second = executor.submit(receive);
                try {
                    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return List.of(first, second);
            });
            var first = futures.get(0).get(10, TimeUnit.SECONDS);
            var second = futures.get(1).get(10, TimeUnit.SECONDS);

            // then
            assertThat(first.id()).isEqualTo(second.id());
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM generation_jobs", Integer.class)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM credit_entries WHERE type = 'CONSUME'",
                    Integer.class)).isEqualTo(1);
            assertThat(balance(userId)).isEqualTo(100 - creditProperties.planRoadmapCourse());
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest(name = "대상={0}")
    @CsvSource({"foreign", "single", "missing"})
    @DisplayName("남의 코스와 단일 코스와 없는 코스는 모두 없는 것으로 답하고 DB를 보존한다")
    void hidesInvalidTargets(String kind) {
        // given
        long userId = user(100);
        long target = switch (kind) {
            case "foreign" -> course(roadmap(user("other@example.com", 100), RoadmapStatus.REVIEWING), 1,
                    CourseStatus.NOT_PLANNED).getId();
            case "single" -> courseRepository.save(CourseFixtures.single(userId)).getId();
            default -> 999999L;
        };
        var before = state();

        // when & then
        assertRejected(userId, target, ErrorCode.NOT_FOUND);
        assertThat(state()).isEqualTo(before);
    }

    @ParameterizedTest(name = "오류={0}, 문구={1}")
    @CsvSource({"COURSE_PLAN_NOT_ALLOWED,지금은 이 코스의 강의 목록을 만들 수 없어요",
            "PREVIOUS_COURSE_NOT_COMPLETED,앞 코스를 다 마치면 만들 수 있어요",
            "REPLAN_IN_PROGRESS,다시 짜는 중에는 시작할 수 없어요"})
    @DisplayName("로드맵 코스 받기 거절은 공통 오류 응답으로 할 일에 맞는 문구와 409를 반환한다")
    void returnsActionableHttpErrors(ErrorCode error, String message) {
        // given
        long userId = user(100);
        var roadmap = roadmap(userId, RoadmapStatus.REVIEWING);
        var course = course(roadmap, 1, error == ErrorCode.COURSE_PLAN_NOT_ALLOWED
                ? CourseStatus.REVIEWING : CourseStatus.NOT_PLANNED);
        if (error == ErrorCode.PREVIOUS_COURSE_NOT_COMPLETED) {
            course = course(roadmap, 2, CourseStatus.NOT_PLANNED);
            new TransactionTemplate(platformTransactionManager).executeWithoutResult(tx ->
                    roadmapRepository.findById(roadmap.getId()).orElseThrow().start());
        } else if (error == ErrorCode.REPLAN_IN_PROGRESS) {
            generationJobRepository.save(GenerationJobFixtures.replanRoadmap(userId, roadmap.getId(), null));
        }
        var before = state();
        var client = testRequests.asUser(userId);

        // when
        var response = client.post().uri("/api/jobs")
                .body(Map.of("type", "PLAN_ROADMAP_COURSE", "courseId", course.getId())).exchange();

        // then
        response.expectStatus().isEqualTo(409).expectBody()
                .jsonPath("$.code").isEqualTo(error.name()).jsonPath("$.message").isEqualTo(message);
        assertThat(state()).isEqualTo(before);
    }

    private long user(int balance) {
        return user("me@example.com", balance);
    }

    private long user(String email, int balance) {
        var user = userRepository.save(UserFixtures.approved(email, balance,
                testClock.instant().plus(Duration.ofDays(30))));
        creditEntryRepository.save(CreditEntry.refill(user, balance));
        return user.getId();
    }

    private Roadmap roadmap(long userId, RoadmapStatus status) {
        return roadmapRepository.save(RoadmapFixtures.withStatus(RoadmapFixtures.roadmap(userId), status));
    }

    private Course course(Roadmap roadmap, int position, CourseStatus status) {
        return courseRepository.save(CourseFixtures.withStatus(CourseFixtures.inRoadmap(roadmap, position,
                "계획 " + position, "설명 " + position, List.of()), status));
    }

    private JobRequest request(long courseId) {
        return new JobRequest(JobType.PLAN_ROADMAP_COURSE, courseId, null, null);
    }

    private void assertRejected(long userId, long courseId, ErrorCode code) {
        assertThatThrownBy(() -> jobIntakeService.receive(userId, request(courseId)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    private List<List<Map<String, Object>>> state() {
        return List.of("users", "roadmaps", "courses", "generation_jobs", "credit_entries").stream()
                .map(table -> jdbcTemplate.queryForList("SELECT * FROM " + table + " ORDER BY id")).toList();
    }

    private int balance(long userId) {
        return userRepository.findById(userId).orElseThrow().getCreditBalance();
    }
}
