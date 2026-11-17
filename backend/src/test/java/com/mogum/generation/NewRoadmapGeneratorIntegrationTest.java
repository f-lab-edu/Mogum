package com.mogum.generation;

import com.mogum.account.domain.CreditEntry;
import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.CreditEntryRepository;
import com.mogum.account.repository.UserRepository;
import com.mogum.account.service.CreditLedger;
import com.mogum.config.CreditProperties;
import com.mogum.config.WorkerProperties;
import com.mogum.course.domain.LearningInput;
import com.mogum.course.domain.Level;
import com.mogum.course.domain.Purpose;
import com.mogum.job.JobIntakeHandler;
import com.mogum.job.JobRequest;
import com.mogum.job.domain.JobType;
import com.mogum.job.service.JobIntakeService;
import com.mogum.support.IntegrationTest;
import com.mogum.support.IntegrationTestConfiguration.TestChatProvider;
import com.mogum.support.IntegrationTestConfiguration.TestJobGenerator;
import com.mogum.support.IntegrationTestConfiguration.TestJobIntakeHandler;
import com.mogum.support.TestClock;
import com.mogum.worker.JobRunner;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class NewRoadmapGeneratorIntegrationTest {
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
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ApplicationContext applicationContext;

    @Test
    @DisplayName("운영 로드맵 처리기는 입력과 계획을 저장하고 모든 코스를 강의 없는 짜기 전으로 만든다")
    void createsRoadmapAndUnplannedCourses() throws InterruptedException {
        // given
        testChatProvider.reset();
        var user = userRepository.save(UserFixtures.approved(
                "me@example.com", 100, testClock.instant().plus(Duration.ofDays(30))));
        creditEntryRepository.save(CreditEntry.refill(user, 100));
        long id = jobIntakeService.receive(user.getId(), new JobRequest(JobType.NEW_ROADMAP, null,
                new LearningInput("자바", Level.OTHER, "조금 알아요", Purpose.OTHER, "연구", "원하는 메모"), null)).id();
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        var runner = new JobRunner(entityManager, platformTransactionManager, creditLedger, testClock,
                workerProperties, generators, scheduler);
        try {
            // when
            boolean processed = runner.runOnce();

            // then
            assertThat(processed).isTrue();
            assertThat(testChatProvider.outsideTransaction(id)).isTrue();
            var job = jdbcTemplate.queryForMap("SELECT * FROM generation_jobs WHERE id = ?", id);
            assertThat(job).containsEntry("status", "SUCCEEDED");
            var roadmap = jdbcTemplate.queryForMap("SELECT * FROM roadmaps WHERE id = ?", job.get("roadmap_id"));
            assertThat(roadmap).containsEntry("status", "REVIEWING").containsEntry("title", "자바 로드맵")
                    .containsEntry("description", "자바를 익힌다").containsEntry("user_id", user.getId())
                    .containsEntry("topic", "자바").containsEntry("level", "OTHER")
                    .containsEntry("level_other", "조금 알아요").containsEntry("purpose", "OTHER")
                    .containsEntry("purpose_other", "연구").containsEntry("memo", "원하는 메모");
            var courses = jdbcTemplate.queryForList("SELECT * FROM courses ORDER BY position");
            assertThat(courses).extracting(row -> row.get("position")).containsExactly(1, 2);
            assertThat(courses).extracting(row -> row.get("title")).containsExactly("기초", "응용");
            assertThat(courses).extracting(row -> row.get("plan_description"))
                    .containsExactly("기초 설명", "응용 설명");
            assertThat(courses).allSatisfy(row -> assertThat(row)
                    .containsEntry("status", "NOT_PLANNED").containsEntry("user_id", user.getId())
                    .containsEntry("roadmap_id", job.get("roadmap_id"))
                    .containsEntry("topic", null).containsEntry("level", null).containsEntry("level_other", null)
                    .containsEntry("purpose", null).containsEntry("purpose_other", null).containsEntry("memo", null));
            assertThat(jdbcTemplate.queryForList(
                    "SELECT plan_concepts -> 0 ->> 'name' FROM courses ORDER BY position", String.class))
                    .containsExactly("변수", "함수");
            assertThat(jdbcTemplate.queryForList(
                    "SELECT plan_concepts -> 0 ->> 'definition' FROM courses ORDER BY position", String.class))
                    .containsExactly("값을 담는 이름", "동작의 묶음");
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM lessons", Integer.class)).isZero();
            assertThat(jdbcTemplate.queryForMap("SELECT * FROM llm_calls"))
                    .containsEntry("job_id", id).containsEntry("user_id", user.getId())
                    .containsEntry("job_type", "NEW_ROADMAP").containsEntry("outcome", "SUCCEEDED");
            assertThat(userRepository.findById(user.getId()).orElseThrow().getCreditBalance())
                    .isEqualTo(100 - creditProperties.newRoadmap());
        } finally {
            scheduler.shutdownNow();
            assertThat(scheduler.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("공용 통합 컨텍스트에는 테스트 받기와 생성 처리기 빈이 없고 운영 처리기만 있다")
    void sharesProductionHandlersOnly() {
        // when
        var intake = applicationContext.getBeansOfType(JobIntakeHandler.class).values();
        var generation = applicationContext.getBeansOfType(JobGenerator.class).values();

        // then
        assertThat(intake).noneMatch(TestJobIntakeHandler.class::isInstance);
        assertThat(generation).noneMatch(TestJobGenerator.class::isInstance);
        assertThat(applicationContext.getBeansOfType(TestJobIntakeHandler.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(TestJobGenerator.class)).isEmpty();
        assertThat(intake).extracting(JobIntakeHandler::type)
                .contains(JobType.NEW_COURSE, JobType.NEW_ROADMAP);
        assertThat(generation).extracting(JobGenerator::type)
                .contains(JobType.NEW_COURSE, JobType.NEW_ROADMAP);
    }
}
