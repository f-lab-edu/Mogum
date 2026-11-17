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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class ReplanCourseGeneratorIntegrationTest {
    @Autowired ReplanCourseGenerator replanCourseGenerator;
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

    @ParameterizedTest(name = "코스 위치={0}")
    @ValueSource(ints = {0, 1, 3})
    @DisplayName("코스 다시 짜기는 입력과 이전 목록과 피드백으로 강의를 교체하고 고친 제목과 계획을 유지한다")
    void replacesLessonsAndPreservesEditedTitle(int position, CapturedOutput output) throws InterruptedException {
        // given
        testChatProvider.reset();
        var course = course(position);
        var before = jdbcTemplate.queryForMap("SELECT * FROM courses WHERE id = ?", course.getId());
        var oldIds = jdbcTemplate.queryForList("SELECT id FROM lessons WHERE course_id = ?",
                Long.class, course.getId());
        long id = jobIntakeService.receive(course.getUserId(), request(course)).id();

        // when
        boolean processed = runOnce();

        // then
        assertThat(processed).isTrue();
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM generation_jobs WHERE id = ?", id))
                .containsEntry("status", "SUCCEEDED").containsEntry("course_id", course.getId());
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM courses WHERE id = ?", course.getId())).isEqualTo(before);
        var lessons = jdbcTemplate.queryForList("SELECT * FROM lessons WHERE course_id = ? ORDER BY position",
                course.getId());
        assertThat(lessons).extracting(row -> row.get("position")).containsExactly(1, 2);
        assertThat(lessons).extracting(row -> row.get("title")).containsExactly("기초", "응용");
        assertThat(lessons).extracting(row -> row.get("description")).containsExactly("기초 설명", "응용 설명");
        assertThat(lessons).extracting(row -> row.get("id")).doesNotContainAnyElementsOf(oldIds);
        assertThat(jdbcTemplate.queryForList(
                "SELECT concepts -> 0 ->> 'name' FROM lessons WHERE course_id = ? ORDER BY position",
                String.class, course.getId())).containsExactly("변수", "함수");
        assertThat(testChatProvider.outsideTransaction(id)).isTrue();
        String materials = testChatProvider.requests(id).getFirst().messages().get(1).text();
        assertThat(materials).contains("LEAKCHECK-topic", "LEAKCHECK-purpose", "LEAKCHECK-memo",
                "previousLessons", "이전 강의", "이전 설명", "이전 개념", "이전 정의", "LEAKCHECK-feedback");
        if (position == 0) {
            assertThat(materials).contains("LEAKCHECK-level")
                    .doesNotContain("coursePlan", "followingCoursePlans", "사용자가 고친 제목");
        } else {
            assertThat(materials).contains("사용자가 고친 제목", "계획설명-" + position,
                    "계획개념-" + position, "계획정의-" + position, "계획-4");
            if (position == 1) {
                assertThat(materials).contains("LEAKCHECK-level", "계획-2", "계획-3")
                        .doesNotContain("previousCourseLessons");
            } else {
                assertThat(materials).contains("previousCourseLessons", "실제-2", "실제설명-2", "실제개념-2")
                        .doesNotContain("LEAKCHECK-level", "\"level\"", "계획-1", "계획-2", "실제-1");
            }
        }
        assertThat(userRepository.findById(course.getUserId()).orElseThrow().getCreditBalance())
                .isEqualTo(100 - creditProperties.replanCourse());
        assertThat(output.getAll()).doesNotContain("LEAKCHECK-", "이전 강의", "사용자가 고친 제목");
    }

    @ParameterizedTest(name = "위치={0}, 실패={1}")
    @CsvSource({"0,PROVIDER_UNAVAILABLE", "0,INVALID_OUTPUT", "0,PROVIDER_REJECTED", "0,INPUT_TOO_LARGE",
            "3,PROVIDER_UNAVAILABLE", "3,INVALID_OUTPUT", "3,PROVIDER_REJECTED", "3,INPUT_TOO_LARGE"})
    @DisplayName("코스 다시 짜기 실패와 버리기는 이전 강의와 제목과 계획을 보존하고 크레딧을 한 번 복구한다")
    void preservesPreviousContentOnFailureAndDiscard(int position, GenerationOutcome.Failure failure)
            throws InterruptedException {
        // given
        testChatProvider.reset();
        var course = course(position);
        var before = content();
        long id = jobIntakeService.receive(course.getUserId(), request(course)).id();
        var replies = new ChatProvider.Reply[workerProperties.maxRetries() + 1];
        Arrays.fill(replies, TestChatProvider.failure(failure));
        testChatProvider.reply(id, replies);
        runOnce();
        assertThat(content()).isEqualTo(before);
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM generation_jobs WHERE id = ?", id))
                .containsEntry("status", "FAILED").containsEntry("error_type", failure.name());

        // when
        jobIntakeService.discard(course.getUserId(), id);

        // then
        assertThat(content()).isEqualTo(before);
        assertThat(userRepository.findById(course.getUserId()).orElseThrow().getCreditBalance()).isEqualTo(100);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM credit_entries WHERE type = 'RESTORE'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM generation_jobs", Integer.class)).isZero();
    }

    @Test
    @DisplayName("없는 코스 재료는 예외 대신 실패로 반환한다")
    void returnsFailureWhenMaterialsAreMissing() {
        // given
        var job = new JobSnapshot(1L, 1L, JobType.REPLAN_COURSE, 99999L, null, null, 1);

        // when
        var outcome = replanCourseGenerator.generate(job);

        // then
        assertThat(outcome).isEqualTo(GenerationOutcome.Failure.PROVIDER_UNAVAILABLE);
    }

    @ParameterizedTest(name = "상태={0}")
    @EnumSource(value = CourseStatus.class, names = {"NOT_PLANNED", "LEARNING", "COMPLETED"})
    @DisplayName("검토 중이 아닌 코스 저장은 불변식 위반으로 이전 강의를 보존한다")
    void rejectsSaveOutsideReviewing(CourseStatus status) {
        // given
        var course = course(1);
        new TransactionTemplate(platformTransactionManager).executeWithoutResult(tx ->
                CourseFixtures.withStatus(courseRepository.findById(course.getId()).orElseThrow(), status));
        var before = content();
        var job = new JobSnapshot(1L, course.getUserId(), JobType.REPLAN_COURSE,
                course.getId(), null, null, 1);
        var result = new ReplanCourseGenerator.Result(new CoursePlan(null,
                List.of(new LessonOutline("강의", "설명", List.of(new PlannedConcept("개념", "정의"))))));

        // when & then
        assertThatThrownBy(() -> new TransactionTemplate(platformTransactionManager).execute(tx ->
                replanCourseGenerator.save(job, result))).isInstanceOf(IllegalStateException.class);
        assertThat(content()).isEqualTo(before);
    }

    @Test
    @DisplayName("강의 삭제 뒤 새 강의 저장이 실패하면 삭제까지 모두 롤백한다")
    void rollsBackReplacementWhenInsertFails() {
        // given
        var course = course(0);
        var before = content();
        var job = new JobSnapshot(1L, course.getUserId(), JobType.REPLAN_COURSE,
                course.getId(), null, null, 1);
        var lessons = IntStream.range(0, 11)
                .mapToObj(index -> new LessonOutline("강의", "설명", List.of(new PlannedConcept("개념", "정의"))))
                .toList();
        var result = new ReplanCourseGenerator.Result(new CoursePlan(null, lessons));

        // when & then
        assertThatThrownBy(() -> new TransactionTemplate(platformTransactionManager).execute(tx ->
                replanCourseGenerator.save(job, result))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(content()).isEqualTo(before);
    }

    private List<?> content() {
        return List.of("roadmaps", "courses", "lessons").stream()
                .map(table -> jdbcTemplate.queryForList("SELECT * FROM " + table + " ORDER BY id")).toList();
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
        var input = new LearningInput("LEAKCHECK-topic", Level.OTHER, "LEAKCHECK-level", Purpose.OTHER,
                "LEAKCHECK-purpose", "LEAKCHECK-memo");
        if (position == 0) {
            var single = courseRepository.save(CourseFixtures.single(user.getId(), input, "사용자가 고친 제목"));
            lessonRepository.save(LessonFixtures.lesson(single, 1, "이전 강의", "이전 설명",
                    List.of(new PlannedConcept("이전 개념", "이전 정의"))));
            return single;
        }
        var roadmap = RoadmapFixtures.roadmap(user.getId(), input, "로드맵", "설명");
        roadmap.start();
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
            if (index == position) {
                new TransactionTemplate(platformTransactionManager).executeWithoutResult(tx ->
                        courseRepository.findById(course.getId()).orElseThrow().planLessons());
                jdbcTemplate.update("UPDATE courses SET title = ? WHERE id = ?", "사용자가 고친 제목", course.getId());
                lessonRepository.save(LessonFixtures.lesson(course, 1, "이전 강의", "이전 설명",
                        List.of(new PlannedConcept("이전 개념", "이전 정의"))));
                target = course;
            }
        }
        return target;
    }

    private JobRequest request(Course course) {
        return new JobRequest(JobType.REPLAN_COURSE, course.getId(), null, "LEAKCHECK-feedback");
    }
}
