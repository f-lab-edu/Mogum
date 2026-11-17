package com.mogum.generation;

import com.mogum.account.domain.CreditEntry;
import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.CreditEntryRepository;
import com.mogum.account.repository.UserRepository;
import com.mogum.account.service.CreditLedger;
import com.mogum.config.CreditProperties;
import com.mogum.config.WorkerProperties;
import com.mogum.course.domain.Course;
import com.mogum.course.domain.CourseFixtures;
import com.mogum.course.domain.CourseStatus;
import com.mogum.course.domain.LearningInput;
import com.mogum.course.domain.LessonFixtures;
import com.mogum.course.domain.Level;
import com.mogum.course.domain.PlannedConcept;
import com.mogum.course.domain.Purpose;
import com.mogum.course.domain.RoadmapFixtures;
import com.mogum.course.repository.CourseRepository;
import com.mogum.course.repository.LessonRepository;
import com.mogum.course.repository.RoadmapRepository;
import com.mogum.generation.plan.CoursePlan;
import com.mogum.generation.plan.LessonOutline;
import com.mogum.job.JobRequest;
import com.mogum.job.domain.JobType;
import com.mogum.job.service.JobIntakeService;
import com.mogum.support.IntegrationTest;
import com.mogum.support.IntegrationTestConfiguration.TestChatProvider;
import com.mogum.support.TestClock;
import com.mogum.worker.JobRunner;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class PlanRoadmapCourseGeneratorIntegrationTest {
    @Autowired PlanRoadmapCourseGenerator planRoadmapCourseGenerator;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager platformTransactionManager;
    @Autowired CreditLedger creditLedger;
    @Autowired WorkerProperties workerProperties;
    @Autowired CreditProperties creditProperties;
    @Autowired TestClock testClock;
    @Autowired List<JobGenerator> generators;
    @Autowired TestChatProvider testChatProvider;
    @Autowired JobIntakeService jobIntakeService;
    @Autowired UserRepository userRepository;
    @Autowired CreditEntryRepository creditEntryRepository;
    @Autowired CourseRepository courseRepository;
    @Autowired RoadmapRepository roadmapRepository;
    @Autowired LessonRepository lessonRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @ParameterizedTest(name = "순서={0}")
    @ValueSource(ints = {1, 3})
    @DisplayName("운영 워커는 첫 코스만 수준을 넣고 바로 앞 실제 목록과 뒤 계획으로 만들어 원래 코스에 저장한다")
    void createsLessonsWithRequiredMaterials(int position, CapturedOutput output) throws InterruptedException {
        // given
        testChatProvider.reset();
        var course = course(position);
        var before = jdbcTemplate.queryForMap("SELECT * FROM courses WHERE id = ?", course.getId());
        long id = jobIntakeService.receive(course.getUserId(), request(course)).id();
        testClock.advance(Duration.ofSeconds(1));

        // when
        boolean processed = runOnce();

        // then
        assertThat(processed).isTrue();
        assertThat(planRoadmapCourseGenerator.type()).isEqualTo(JobType.PLAN_ROADMAP_COURSE);
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM generation_jobs WHERE id = ?", id))
                .containsEntry("status", "SUCCEEDED").containsEntry("course_id", course.getId());
        var saved = jdbcTemplate.queryForMap("SELECT * FROM courses WHERE id = ?", course.getId());
        assertThat(saved).containsEntry("status", "REVIEWING");
        for (String field : List.of("title", "plan_description", "plan_concepts", "roadmap_id", "position", "topic")) {
            assertThat(saved.get(field)).isEqualTo(before.get(field));
        }
        assertThat(courseRepository.findById(course.getId()).orElseThrow().getUpdatedAt())
                .isEqualTo(testClock.instant());
        var lessons = jdbcTemplate.queryForList("SELECT * FROM lessons WHERE course_id = ? ORDER BY position",
                course.getId());
        assertThat(lessons).extracting(row -> row.get("position")).containsExactly(1, 2);
        assertThat(lessons).extracting(row -> row.get("title")).containsExactly("기초", "응용");
        assertThat(lessons).extracting(row -> row.get("description")).containsExactly("기초 설명", "응용 설명");
        assertThat(jdbcTemplate.queryForList(
                "SELECT concepts -> 0 ->> 'name' FROM lessons WHERE course_id = ? ORDER BY position",
                String.class, course.getId())).containsExactly("변수", "함수");
        assertThat(testChatProvider.outsideTransaction(id)).isTrue();
        var messages = testChatProvider.requests(id).getFirst().messages();
        String materials = messages.get(1).text();
        assertThat(materials).contains("LEAKCHECK-topic", "LEAKCHECK-purpose", "LEAKCHECK-memo",
                "계획-" + position, "계획설명-" + position, "계획개념-" + position, "계획정의-" + position);
        if (position == 1) {
            assertThat(materials).contains("LEAKCHECK-level").doesNotContain("previousCourseLessons");
            assertThat(materials).contains("계획-2", "계획-3", "계획-4");
        } else {
            assertThat(materials).doesNotContain("LEAKCHECK-level", "\"level\"", "계획-1", "계획-2", "실제-1")
                    .contains("previousCourseLessons", "실제-2", "실제설명-2", "실제개념-2", "실제정의-2", "계획-4");
        }
        assertThat(userRepository.findById(course.getUserId()).orElseThrow().getCreditBalance())
                .isEqualTo(100 - creditProperties.planRoadmapCourse());
        assertThat(output.getAll()).doesNotContain("LEAKCHECK-", "계획개념-", "실제개념-");
    }

    @ParameterizedTest(name = "실패={0}")
    @EnumSource(GenerationOutcome.Failure.class)
    @DisplayName("생성 실패는 코스를 짜기 전으로 남기고 빈 강의 없이 크레딧을 복구한다")
    void keepsCourseUnplannedOnFailure(GenerationOutcome.Failure failure) throws InterruptedException {
        // given
        testChatProvider.reset();
        var course = course(1);
        var before = jdbcTemplate.queryForMap("SELECT * FROM courses WHERE id = ?", course.getId());
        long id = jobIntakeService.receive(course.getUserId(), request(course)).id();
        var replies = new ChatProvider.Reply[workerProperties.maxRetries() + 1];
        Arrays.fill(replies, TestChatProvider.failure(failure));
        testChatProvider.reply(id, replies);

        // when
        runOnce();

        // then
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM courses WHERE id = ?", course.getId())).isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM lessons WHERE course_id = ?",
                Integer.class, course.getId())).isZero();
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM generation_jobs WHERE id = ?", id))
                .containsEntry("status", "FAILED").containsEntry("error_type", failure.name());
        assertThat(userRepository.findById(course.getUserId()).orElseThrow().getCreditBalance()).isEqualTo(100);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM credit_entries WHERE type = 'RESTORE'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("재료를 읽지 못해도 예외 대신 실패를 반환한다")
    void returnsFailureWhenMaterialsAreMissing() {
        // given
        var job = new JobSnapshot(1L, 1L, JobType.PLAN_ROADMAP_COURSE, 99999L, null, null, 1);

        // when
        var outcome = planRoadmapCourseGenerator.generate(job);

        // then
        assertThat(outcome).isEqualTo(GenerationOutcome.Failure.PROVIDER_UNAVAILABLE);
    }

    @Test
    @DisplayName("저장할 코스가 짜기 전이 아니면 불변식 위반으로 강의를 쓰지 않고 실패한다")
    void rejectsSaveAfterCourseWasPlanned() {
        // given
        var course = course(1);
        new TransactionTemplate(platformTransactionManager).executeWithoutResult(tx ->
                courseRepository.findById(course.getId()).orElseThrow().planLessons());
        var job = new JobSnapshot(1L, course.getUserId(), JobType.PLAN_ROADMAP_COURSE,
                course.getId(), null, null, 1);
        var result = new PlanRoadmapCourseGenerator.Result(new CoursePlan(null,
                List.of(new LessonOutline("강의", "설명", List.of(new PlannedConcept("개념", "정의"))))));

        // when & then
        assertThatThrownBy(() -> new TransactionTemplate(platformTransactionManager).execute(tx ->
                planRoadmapCourseGenerator.save(job, result))).isInstanceOf(IllegalStateException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM lessons WHERE course_id = ?",
                Integer.class, course.getId())).isZero();
    }

    private boolean runOnce() throws InterruptedException {
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            return new JobRunner(entityManager, platformTransactionManager, creditLedger, testClock,
                    workerProperties, generators, scheduler).runOnce();
        } finally {
            scheduler.shutdownNow();
            assertThat(scheduler.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Course course(int position) {
        var user = userRepository.save(UserFixtures.approved("me@example.com", 100,
                testClock.instant().plus(Duration.ofDays(30))));
        creditEntryRepository.save(CreditEntry.refill(user, 100));
        var roadmap = RoadmapFixtures.roadmap(user.getId(),
                new LearningInput("LEAKCHECK-topic", Level.OTHER, "LEAKCHECK-level", Purpose.OTHER,
                        "LEAKCHECK-purpose", "LEAKCHECK-memo"), "로드맵", "설명");
        if (position > 1) roadmap.start();
        roadmapRepository.save(roadmap);
        Course target = null;
        for (int index = 1; index <= 4; index++) {
            var course = CourseFixtures.inRoadmap(roadmap, index, "계획-" + index, "계획설명-" + index,
                    List.of(new PlannedConcept("계획개념-" + index, "계획정의-" + index)));
            if (index < position) CourseFixtures.withStatus(course, CourseStatus.COMPLETED);
            courseRepository.save(course);
            if (index < position) {
                lessonRepository.save(LessonFixtures.lesson(course, 1, "실제-" + index, "실제설명-" + index,
                        List.of(new PlannedConcept("실제개념-" + index, "실제정의-" + index))));
            }
            if (index == position) target = course;
        }
        return target;
    }

    private JobRequest request(Course course) {
        return new JobRequest(JobType.PLAN_ROADMAP_COURSE, course.getId(), null, null);
    }
}
