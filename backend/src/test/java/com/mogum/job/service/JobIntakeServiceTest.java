package com.mogum.job.service;

import com.mogum.account.domain.CreditEntry;
import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.FakeCreditEntryRepository;
import com.mogum.account.repository.FakeUserRepository;
import com.mogum.account.service.CreditLedger;
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.config.CreditProperties;
import com.mogum.config.JobProperties;
import com.mogum.course.domain.*;
import com.mogum.course.repository.*;
import com.mogum.job.JobIntakeHandler;
import com.mogum.job.JobRequest;
import com.mogum.job.domain.*;
import com.mogum.job.repository.FakeGenerationJobRepository;
import com.mogum.job.repository.GenerationJobRepository;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobIntakeServiceTest {
    private final FakeUserRepository users = new FakeUserRepository();
    private final FakeCreditEntryRepository entries = new FakeCreditEntryRepository();
    private final CreditLedger ledger = new CreditLedger(users, entries);
    private final FakeGenerationJobRepository jobs = new FakeGenerationJobRepository();
    private final FakeCourseRepository courses = new FakeCourseRepository();
    private final FakeRoadmapRepository roadmaps = new FakeRoadmapRepository();
    private final FakeLessonRepository lessons = new FakeLessonRepository();
    private final CreditProperties credits = new CreditProperties(100, 2, 3, 4, 5, 6, 7);
    private final JobIntakeService service = service(List.of(JobType.values()).stream().map(type ->
            (JobIntakeHandler) new JobIntakeHandler() {
                public JobType type() { return type; }
                public void check(long userId, JobRequest request) {
                    if ("reject".equals(request.feedback())) throw new ApiException(ErrorCode.INVALID_REQUEST);
                }
            }).toList());

    @ParameterizedTest
    @EnumSource(JobType.class)
    @DisplayName("운영처럼 처리기 빈이 하나도 없어도 기동하며 어떤 종류도 받지 않는다")
    void rejectsMissingHandler(JobType type) {
        // given
        long userId = user(100);
        var request = request(type, userId);
        var runner = new ApplicationContextRunner().withUserConfiguration(JobIntakeService.class)
                .withBean(GenerationJobRepository.class, () -> jobs)
                .withBean(CourseRepository.class, () -> courses)
                .withBean(RoadmapRepository.class, () -> roadmaps)
                .withBean(LessonRepository.class, () -> lessons)
                .withBean(CreditLedger.class, () -> ledger)
                .withBean(CreditProperties.class, () -> credits)
                .withBean(JobProperties.class, () -> new JobProperties(3));

        // when
        runner.run(context -> {
            // then
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(JobIntakeHandler.class)).isEmpty();
            assertError(() -> context.getBean(JobIntakeService.class).receive(userId, request), ErrorCode.INVALID_REQUEST);
            assertThat(jobs.size()).isZero();
            assertThat(entries.entries()).hasSize(1);
        });
    }

    @ParameterizedTest
    @EnumSource(value = JobType.class, names = {"MATERIAL", "PLAN_ROADMAP_COURSE", "REPLAN_COURSE", "REPLAN_ROADMAP"})
    @DisplayName("대상이 없거나 남의 것이면 처리기보다 먼저 없는 것으로 답한다")
    void rejectsForeignAndMissingTargetsBeforeHandler(JobType type) {
        // given
        long userId = user(100);
        var foreign = request(type, user(100));
        var rejectAll = service(List.of(new JobIntakeHandler() {
            public JobType type() { return type; }
            public void check(long id, JobRequest request) { throw new AssertionError("ownership must run first"); }
        }));

        // when & then
        assertError(() -> rejectAll.receive(userId, foreign), ErrorCode.NOT_FOUND);
        assertError(() -> rejectAll.receive(userId, new JobRequest(type, 999L, null, null)), ErrorCode.NOT_FOUND);
        assertThat(jobs.size()).isZero();
    }

    @Test
    @DisplayName("처리기가 거절하면 작업과 크레딧을 바꾸지 않는다")
    void preservesDataOnHandlerRejection() {
        // given
        long userId = user(100);
        var target = request(JobType.REPLAN_COURSE, userId);
        var request = new JobRequest(target.type(), target.targetId(), null, "reject");

        // when & then
        assertError(() -> service.receive(userId, request), ErrorCode.INVALID_REQUEST);
        assertThat(jobs.size()).isZero();
        assertThat(users.findById(userId).orElseThrow().getCreditBalance()).isEqualTo(100);
        assertThat(entries.entries()).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(value = JobType.class, names = {"MATERIAL", "PLAN_ROADMAP_COURSE", "REPLAN_COURSE", "REPLAN_ROADMAP"})
    @DisplayName("같은 대상은 상한과 잔액 확인 없이 기존 실패 작업을 돌려준다")
    void returnsExistingBeforeLimitAndCreditCheck(JobType type) {
        // given
        long userId = user(100);
        var request = request(type, userId);
        var first = service.receive(userId, request);
        GenerationJobFixtures.failed(jobs.findById(first.id()).orElseThrow(), JobErrorType.INVALID_OUTPUT);
        for (int i = 0; i < 3; i++) jobs.save(GenerationJobFixtures.newCourse(userId, input()));
        users.findById(userId).orElseThrow().consumeCredits(users.findById(userId).orElseThrow().getCreditBalance());
        int entryCount = entries.entries().size();

        // when
        var second = service.receive(userId, request);

        // then
        assertThat(second).isEqualTo(new JobResult(first.id(), JobStatus.FAILED));
        assertThat(jobs.size()).isEqualTo(4);
        assertThat(entries.entries()).hasSize(entryCount);
    }

    @Test
    @DisplayName("만드는 중과 기다림을 모든 종류 함께 세어 상한에서 거절한다")
    void rejectsAtLimitAcrossTypes() {
        // given
        long userId = user(100);
        jobs.save(GenerationJobFixtures.newCourse(userId, input()));
        jobs.save(GenerationJobFixtures.newRoadmap(userId, input()));
        var running = jobs.save(GenerationJobFixtures.newCourse(userId, input()));
        GenerationJobFixtures.running(running);

        // when & then
        assertError(() -> service.receive(userId, request(JobType.NEW_COURSE, userId)), ErrorCode.TOO_MANY_IN_PROGRESS);
        assertThat(jobs.size()).isEqualTo(3);
        assertThat(entries.entries()).hasSize(1);
    }

    @Test
    @DisplayName("크레딧이 모자라면 공통 크레딧 부족 오류를 그대로 전파한다")
    void propagatesInsufficientCredit() {
        // given
        long userId = user(0);

        // when & then
        assertError(() -> service.receive(userId, request(JobType.NEW_COURSE, userId)), ErrorCode.CREDIT_INSUFFICIENT);
        assertThat(entries.entries()).hasSize(1);
        // Fake는 트랜잭션 롤백을 흉내 내지 않는다. 작업 롤백은 통합 시험에서 본다.
    }

    @ParameterizedTest
    @EnumSource(JobType.class)
    @DisplayName("종류마다 설정한 크레딧으로 새 작업을 받고 값만 돌려준다")
    void consumesConfiguredAmount(JobType type) {
        // given
        long userId = user(100);
        var request = request(type, userId);

        // when
        var result = service.receive(userId, request);

        // then
        assertThat(result.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(jobs.findById(result.id())).isPresent();
        assertThat(entries.entries()).last().satisfies(entry -> {
            assertThat(entry.getJobId()).isEqualTo(result.id());
            assertThat(entry.getAmount()).isEqualTo(-switch (type) {
                case NEW_COURSE -> 2;
                case NEW_ROADMAP -> 3;
                case PLAN_ROADMAP_COURSE -> 4;
                case REPLAN_COURSE -> 5;
                case REPLAN_ROADMAP -> 6;
                case MATERIAL -> 7;
            });
        });
    }

    @ParameterizedTest
    @EnumSource(value = JobStatus.class, names = {"QUEUED", "RUNNING", "SUCCEEDED"})
    @DisplayName("실패가 아니면 다시 시도는 현재 작업을 그대로 돌려준다")
    void returnsNonFailedOnRetry(JobStatus status) {
        // given
        long userId = user(100);
        var job = jobs.save(GenerationJobFixtures.material(userId, 1));
        GenerationJobFixtures.withStatus(job, status);

        // when
        var result = service.retry(userId, job.getId());

        // then
        assertThat(result).isEqualTo(new JobResult(job.getId(), status));
        assertThat(entries.entries()).hasSize(1);
    }

    @Test
    @DisplayName("입력이 너무 큰 실패는 다시 시도하지 않는다")
    void rejectsInputTooLargeRetry() {
        // given
        long userId = user(100);
        var job = failed(userId, JobErrorType.INPUT_TOO_LARGE);

        // when & then
        assertError(() -> service.retry(userId, job.getId()), ErrorCode.JOB_RETRY_NOT_ALLOWED);
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(entries.entries()).hasSize(1);
    }

    @Test
    @DisplayName("실패를 다시 시도할 때도 상한을 확인한다")
    void rejectsRetryAtLimit() {
        // given
        long userId = user(100);
        var job = failed(userId, JobErrorType.INVALID_OUTPUT);
        for (int i = 0; i < 3; i++) jobs.save(GenerationJobFixtures.newCourse(userId, input()));

        // when & then
        assertError(() -> service.retry(userId, job.getId()), ErrorCode.TOO_MANY_IN_PROGRESS);
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
    }

    @Test
    @DisplayName("실패를 다시 시도하면 오류와 끝난 때를 비우고 크레딧을 쓴다")
    void queuesRetryAndConsumesCredit() {
        // given
        long userId = user(100);
        var job = failed(userId, JobErrorType.PROVIDER_REJECTED);

        // when
        var result = service.retry(userId, job.getId());

        // then
        assertThat(result).isEqualTo(new JobResult(job.getId(), JobStatus.QUEUED));
        assertThat(job.getErrorType()).isNull();
        assertThat(job.getFinishedAt()).isNull();
        assertThat(entries.entries()).last().satisfies(entry -> assertThat(entry.getAmount()).isEqualTo(-2));
    }

    @ParameterizedTest
    @EnumSource(value = JobStatus.class, names = {"QUEUED", "RUNNING", "SUCCEEDED"})
    @DisplayName("실패가 아닌 작업은 버릴 수 없다")
    void rejectsDiscardForNonFailed(JobStatus status) {
        // given
        long userId = user(100);
        var job = jobs.save(GenerationJobFixtures.material(userId, 1));
        GenerationJobFixtures.withStatus(job, status);

        // when & then
        assertError(() -> service.discard(userId, job.getId()), ErrorCode.JOB_DISCARD_NOT_ALLOWED);
        assertThat(jobs.size()).isEqualTo(1);
        assertThat(entries.entries()).hasSize(1);
    }

    @Test
    @DisplayName("버리기는 아직 빚진 크레딧을 돌려주고 작업을 지운다")
    void restoresAndDiscardsFailedJob() {
        // given
        long userId = user(100);
        var job = failed(userId, JobErrorType.INVALID_OUTPUT);
        ledger.consume(userId, job.getId(), 2);

        // when
        service.discard(userId, job.getId());

        // then
        assertThat(jobs.findById(job.getId())).isEmpty();
        assertThat(users.findById(userId).orElseThrow().getCreditBalance()).isEqualTo(100);
        assertThat(entries.entries()).hasSize(3);
    }

    @Test
    @DisplayName("남의 작업과 없는 작업의 다시 시도 및 버리기는 없는 것으로 답한다")
    void rejectsForeignAndMissingJobs() {
        // given
        long userId = user(100);
        var job = failed(user(100), JobErrorType.INVALID_OUTPUT);

        // when & then
        assertError(() -> service.retry(userId, job.getId()), ErrorCode.NOT_FOUND);
        assertError(() -> service.discard(userId, job.getId()), ErrorCode.NOT_FOUND);
        assertError(() -> service.retry(userId, 999), ErrorCode.NOT_FOUND);
        assertError(() -> service.discard(userId, 999), ErrorCode.NOT_FOUND);
        assertThat(jobs.size()).isEqualTo(1);
        assertThat(entries.entries()).hasSize(2);
    }

    private JobIntakeService service(List<JobIntakeHandler> handlers) {
        return new JobIntakeService(jobs, courses, roadmaps, lessons, ledger, credits, new JobProperties(3), handlers);
    }

    private long user(int amount) {
        var user = users.save(UserFixtures.approved("me@example.com", amount, Instant.parse("2026-12-01T19:00:00Z")));
        entries.save(CreditEntry.refill(user, amount));
        return user.getId();
    }

    private GenerationJob failed(long userId, JobErrorType error) {
        var job = jobs.save(GenerationJobFixtures.newCourse(userId, input()));
        GenerationJobFixtures.failed(job, error);
        return job;
    }

    private JobRequest request(JobType type, long owner) {
        Long target = switch (type) {
            case MATERIAL -> lessons.save(LessonFixtures.lesson(courses.save(CourseFixtures.single(owner)), 1)).getId();
            case PLAN_ROADMAP_COURSE, REPLAN_COURSE -> courses.save(CourseFixtures.single(owner)).getId();
            case REPLAN_ROADMAP -> roadmaps.save(RoadmapFixtures.roadmap(owner)).getId();
            default -> null;
        };
        return new JobRequest(type, target, target == null ? input() : null, null);
    }

    private static LearningInput input() {
        return new LearningInput("자바", Level.BEGINNER, null, Purpose.WORK, null, null);
    }

    private static void assertError(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.errorCode()).isEqualTo(code));
    }
}
