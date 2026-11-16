package com.mogum.job.controller;

import com.mogum.account.domain.CreditEntry;
import com.mogum.account.domain.User;
import com.mogum.account.domain.UserFixtures;
import com.mogum.account.domain.UserStatus;
import com.mogum.account.repository.CreditEntryRepository;
import com.mogum.account.repository.UserRepository;
import com.mogum.account.service.CreditLedger;
import com.mogum.auth.AccountPrincipal;
import com.mogum.config.JobProperties;
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
import com.mogum.job.domain.JobType;
import com.mogum.job.repository.GenerationJobRepository;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestRequests;
import com.mogum.support.TestSessions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class JobControllerIntegrationTest {
    @Autowired TestRequests testRequests;
    @Autowired UserRepository userRepository;
    @Autowired CreditEntryRepository creditEntryRepository;
    @Autowired GenerationJobRepository generationJobRepository;
    @Autowired CourseRepository courseRepository;
    @Autowired RoadmapRepository roadmapRepository;
    @Autowired LessonRepository lessonRepository;
    @Autowired CreditLedger creditLedger;
    @Autowired JobProperties jobProperties;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired JdbcIndexedSessionRepository jdbcIndexedSessionRepository;

    @Test
    @DisplayName("받기와 읽기와 실패 다시 시도와 버리기를 실제 HTTP로 한 바퀴 수행한다")
    void completesJobLifecycleOverHttp() {
        // given
        var user = approved(100);
        var client = testRequests.asUser(user.getId());

        // when — 받기
        var received = client.post().uri("/api/jobs").body(newCourse()).exchange();

        // then
        long id = jdbcTemplate.queryForObject("SELECT id FROM generation_jobs", Long.class);
        received.expectStatus().isOk().expectBody().json(queued(id), JsonCompareMode.STRICT);
        client.get().uri("/api/jobs/" + id).exchange().expectStatus().isOk()
                .expectBody().json(queued(id), JsonCompareMode.STRICT);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getCreditBalance()).isEqualTo(99);

        // given — 워커의 실패 · 복구를 SQL 픽스처와 원장으로 준비한다.
        fail(id, JobErrorType.PROVIDER_UNAVAILABLE);
        creditLedger.restoreIfOwed(user.getId(), id);
        client.get().uri("/api/jobs/" + id).exchange().expectStatus().isOk().expectBody().json("""
                {"data":{"id":%d,"type":"NEW_COURSE","status":"FAILED",
                 "subject":{"topic":"자바","level":"BEGINNER","purpose":"WORK"},
                 "error":{"code":"JOB_TEMPORARY_FAILURE","message":"만들지 못했습니다. 다시 시도하거나 버려 주세요"}}}
                """.formatted(id), JsonCompareMode.STRICT);

        // when — 다시 시도
        var retried = client.post().uri("/api/jobs/" + id + "/retry").exchange();

        // then
        retried.expectStatus().isOk().expectBody().json(queued(id), JsonCompareMode.STRICT);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getCreditBalance()).isEqualTo(99);

        // given
        fail(id, JobErrorType.INVALID_OUTPUT);

        // when — 버리기
        var discarded = client.post().uri("/api/jobs/" + id + "/discard").exchange();

        // then
        discarded.expectStatus().isNoContent().expectBody().isEmpty();
        client.get().uri("/api/jobs/" + id).exchange().expectStatus().isNotFound()
                .expectBody().json(notFound(), JsonCompareMode.STRICT);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getCreditBalance()).isEqualTo(100);
        assertThat(jdbcTemplate.queryForObject("SELECT sum(amount) FROM credit_entries", Integer.class)).isEqualTo(100);
    }

    @ParameterizedTest(name = "동작={0}")
    @ValueSource(strings = {"read", "retry", "discard"})
    @DisplayName("남의 작업과 없는 작업은 같은 404이고 원문과 작업 정보가 전혀 없다")
    void hidesForeignAndMissingJobs(String action) {
        // given
        var owner = approved(100);
        var other = userRepository.save(UserFixtures.approved("other@example.com"));
        var job = generationJobRepository.save(GenerationJobFixtures.newCourse(owner.getId(),
                new LearningInput("LEAKCHECK-private-topic", Level.BEGINNER, null, Purpose.WORK, null, "LEAKCHECK-private-memo")));
        fail(job.getId(), JobErrorType.INVALID_OUTPUT);
        var before = jdbcTemplate.queryForMap("SELECT * FROM generation_jobs");
        var client = testRequests.asUser(other.getId());

        // when
        var foreign = operation(client, action, job.getId());
        var missing = operation(client, action, job.getId() + 1000);

        // then
        foreign.expectStatus().isNotFound().expectBody().json(notFound(), JsonCompareMode.STRICT);
        missing.expectStatus().isNotFound().expectBody().json(notFound(), JsonCompareMode.STRICT);
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM generation_jobs")).isEqualTo(before);
        assertThat(userRepository.findById(owner.getId()).orElseThrow().getCreditBalance()).isEqualTo(100);
    }

    @ParameterizedTest(name = "실패 까닭={0}")
    @EnumSource(JobErrorType.class)
    @DisplayName("실패 응답은 공통 오류 객체와 행동 묶음만 싣고 운영 정보와 메모를 제외한다")
    void returnsOnlyPublicFailureFields(JobErrorType cause) {
        // given
        var user = approved(100);
        var job = generationJobRepository.save(GenerationJobFixtures.newRoadmap(user.getId(), new LearningInput(
                "물리", Level.OTHER, "수학은 알아요", Purpose.OTHER, "연구", "LEAKCHECK-private-memo")));
        fail(job.getId(), cause);
        var client = testRequests.asUser(user.getId());
        boolean input = cause == JobErrorType.INPUT_TOO_LARGE;

        // when
        var response = client.get().uri("/api/jobs/" + job.getId()).exchange();

        // then
        response.expectStatus().isOk().expectBody().json("""
                {"data":{"id":%d,"type":"NEW_ROADMAP","status":"FAILED",
                 "subject":{"topic":"물리","level":"OTHER","levelOther":"수학은 알아요","purpose":"OTHER","purposeOther":"연구"},
                 "error":{"code":"%s","message":"%s"}}}
                """.formatted(job.getId(), input ? "JOB_INPUT_FAILURE" : "JOB_TEMPORARY_FAILURE",
                input ? "주제를 좁혀 새로 만들어 주세요" : "만들지 못했습니다. 다시 시도하거나 버려 주세요"), JsonCompareMode.STRICT);
    }

    @ParameterizedTest(name = "종류={0}")
    @EnumSource(JobType.class)
    @DisplayName("성공 응답은 여섯 종류의 이동할 결과를 객체로 싣고 강의는 코스 ID도 싣는다")
    void returnsSuccessDestination(JobType type) {
        // given
        var user = approved(100);
        var course = courseRepository.save(CourseFixtures.single(user.getId()));
        var roadmap = roadmapRepository.save(RoadmapFixtures.roadmap(user.getId()));
        var lesson = lessonRepository.save(LessonFixtures.lesson(course, 1));
        var input = new LearningInput("자바", Level.BEGINNER, null, Purpose.WORK, null, "비공개 메모");
        var job = generationJobRepository.save(switch (type) {
            case NEW_COURSE -> GenerationJobFixtures.newCourse(user.getId(), input);
            case NEW_ROADMAP -> GenerationJobFixtures.newRoadmap(user.getId(), input);
            case PLAN_ROADMAP_COURSE -> GenerationJobFixtures.planRoadmapCourse(user.getId(), course.getId());
            case REPLAN_COURSE -> GenerationJobFixtures.replanCourse(user.getId(), course.getId(), "비공개 피드백");
            case REPLAN_ROADMAP -> GenerationJobFixtures.replanRoadmap(user.getId(), roadmap.getId(), "비공개 피드백");
            case MATERIAL -> GenerationJobFixtures.material(user.getId(), lesson.getId());
        });
        boolean courseResult = type == JobType.NEW_COURSE || type == JobType.PLAN_ROADMAP_COURSE || type == JobType.REPLAN_COURSE;
        boolean roadmapResult = type == JobType.NEW_ROADMAP || type == JobType.REPLAN_ROADMAP;
        GenerationJobFixtures.succeeded(jdbcTemplate, job.getId(), courseResult ? course.getId() : null,
                roadmapResult ? roadmap.getId() : null, type == JobType.MATERIAL ? lesson.getId() : null,
                Instant.parse("2026-11-15T00:00:00Z"));
        String subject = switch (type) {
            case NEW_COURSE, NEW_ROADMAP -> "\"topic\":\"자바\",\"level\":\"BEGINNER\",\"purpose\":\"WORK\"";
            case MATERIAL -> "\"title\":\"" + lesson.getTitle() + "\"";
            case REPLAN_ROADMAP -> "\"title\":\"" + roadmap.getTitle() + "\"";
            default -> "\"title\":\"" + course.getTitle() + "\"";
        };
        String result = courseResult ? "\"type\":\"COURSE\",\"id\":" + course.getId()
                : roadmapResult ? "\"type\":\"ROADMAP\",\"id\":" + roadmap.getId()
                : "\"type\":\"LESSON\",\"id\":" + lesson.getId() + ",\"courseId\":" + course.getId();
        var client = testRequests.asUser(user.getId());

        // when
        var response = client.get().uri("/api/jobs/" + job.getId()).exchange();

        // then
        response.expectStatus().isOk().expectBody().json("""
                {"data":{"id":%d,"type":"%s","status":"SUCCEEDED","subject":{%s},"result":{%s}}}
                """.formatted(job.getId(), type, subject, result), JsonCompareMode.STRICT);
    }

    @ParameterizedTest(name = "동작={0}")
    @ValueSource(strings = {"receive", "retry"})
    @DisplayName("크레딧 부족은 한국 날짜를 넣은 공통 오류로 반환한다")
    void reportsCreditShortage(String action) {
        // given
        var user = approved(0);
        var job = generationJobRepository.save(GenerationJobFixtures.newCourse(user.getId(), input()));
        fail(job.getId(), JobErrorType.INVALID_OUTPUT);
        var client = testRequests.asUser(user.getId());

        // when
        var result = operation(client, action, job.getId());

        // then
        result.expectStatus().isEqualTo(409).expectBody().json("""
                {"code":"CREDIT_INSUFFICIENT","message":"크레딧이 부족합니다. 2026-12-09에 다시 채워집니다"}
                """, JsonCompareMode.STRICT);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM generation_jobs", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM generation_jobs", String.class)).isEqualTo("FAILED");
    }

    @ParameterizedTest(name = "동작={0}")
    @ValueSource(strings = {"receive", "retry"})
    @DisplayName("만드는 중 상한은 공통 오류 묶음과 문구를 반환한다")
    void reportsInProgressLimit(String action) {
        // given
        var user = approved(100);
        var failed = generationJobRepository.save(GenerationJobFixtures.newCourse(user.getId(), input()));
        fail(failed.getId(), JobErrorType.INVALID_OUTPUT);
        for (int i = 0; i < jobProperties.maxInProgressPerUser(); i++) generationJobRepository.save(GenerationJobFixtures.newCourse(user.getId(), input()));
        var client = testRequests.asUser(user.getId());

        // when
        var result = operation(client, action, failed.getId());

        // then
        result.expectStatus().isEqualTo(409).expectBody().json("""
                {"code":"TOO_MANY_IN_PROGRESS","message":"지금 만드는 것이 끝나면 다시 시도해 주세요"}
                """, JsonCompareMode.STRICT);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM generation_jobs", Integer.class)).isEqualTo(jobProperties.maxInProgressPerUser() + 1);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getCreditBalance()).isEqualTo(100);
    }

    @ParameterizedTest(name = "동작={0}")
    @ValueSource(strings = {"receive", "retry", "discard"})
    @DisplayName("모든 보내기는 CSRF 토큰 없이는 거절한다")
    void requiresCsrf(String action) {
        // given
        var user = approved(100);
        var cookie = new TestSessions(jdbcIndexedSessionRepository).create(user.getId());
        var client = testRequests.anonymous().mutate().defaultCookie(cookie.getName(), cookie.getValue()).build();

        // when
        var result = operation(client, action, 999L);

        // then
        result.expectStatus().isForbidden().expectBody().json("""
                {"code":"CSRF_INVALID","message":"요청을 확인할 수 없습니다"}
                """, JsonCompareMode.STRICT);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM generation_jobs", Integer.class)).isZero();
    }

    @ParameterizedTest(name = "상태={0}")
    @ValueSource(strings = {"NOT_APPLIED", "APPLIED", "REJECTED"})
    @DisplayName("신청 전과 신청과 거절 계정은 네 작업 주소 모두 승인 게이트가 막는다")
    void requiresApprovedAccount(String status) {
        // given
        RestTestClient client;
        if (status.equals("NOT_APPLIED")) {
            var cookie = new TestSessions(jdbcIndexedSessionRepository).createNotApplied(new AccountPrincipal.NotApplied("google", "sub", "me@example.com", "나"));
            var sessionClient = testRequests.anonymous().mutate().defaultCookie(cookie.getName(), cookie.getValue()).build();
            var token = sessionClient.get().uri("/api/me").exchange().returnResult().getResponseCookies().getFirst("XSRF-TOKEN");
            client = sessionClient.mutate().defaultCookie("XSRF-TOKEN", token.getValue()).defaultHeader("X-XSRF-TOKEN", token.getValue()).build();
        } else {
            var user = userRepository.save(UserFixtures.withStatus(UserFixtures.user("me@example.com"), UserStatus.valueOf(status)));
            client = testRequests.asUser(user.getId());
        }

        // when & then
        for (var action : new String[]{"receive", "read", "retry", "discard"}) {
            operation(client, action, 999L).expectStatus().isForbidden().expectBody().json("""
                    {"code":"ACCOUNT_NOT_APPROVED","message":"승인된 계정이 필요합니다"}
                    """, JsonCompareMode.STRICT);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM generation_jobs", Integer.class)).isZero();
    }

    @ParameterizedTest(name = "깨진 JSON={0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("깨진 JSON과 칸 오류의 요청 원문은 응답과 로그에 남지 않는다")
    void keepsInvalidBodiesOutOfLogs(boolean malformed, CapturedOutput output) {
        // given
        var user = approved(100);
        var client = testRequests.asUser(user.getId());
        var marker = "LEAKCHECK-job-request-body";
        var body = malformed ? "{\"topic\":\"" + marker + "\",\"type\":"
                : "{\"type\":\"" + marker + "\",\"topic\":\"" + marker + "\"}";

        // when
        var result = client.post().uri("/api/jobs").contentType(MediaType.APPLICATION_JSON).body(body).exchange();

        // then
        result.expectStatus().isBadRequest().expectBody().json(malformed ? """
                {"code":"INVALID_REQUEST","message":"요청을 확인할 수 없습니다"}
                """ : """
                {"code":"FIELD_INVALID","message":"입력을 확인해 주십시오",
                 "fields":[{"field":"type","reason":"INVALID_VALUE"}]}
                """, JsonCompareMode.STRICT);
        assertThat(output.getAll()).contains("method=POST", "path=/api/jobs", "status=400").doesNotContain(marker, "Unhandled API exception");
    }

    private User approved(int balance) {
        var user = userRepository.save(UserFixtures.approved("me@example.com", balance, Instant.parse("2026-12-08T15:00:00Z")));
        creditEntryRepository.save(CreditEntry.refill(user, balance));
        return user;
    }

    private void fail(long id, JobErrorType error) {
        GenerationJobFixtures.failed(jdbcTemplate, id, error, Instant.parse("2026-11-15T00:00:00Z"));
    }

    private RestTestClient.ResponseSpec operation(RestTestClient client, String action, long id) {
        return switch (action) {
            case "receive" -> client.post().uri("/api/jobs").body(newCourse()).exchange();
            case "read" -> client.get().uri("/api/jobs/" + id).exchange();
            default -> client.post().uri("/api/jobs/" + id + "/" + action).exchange();
        };
    }

    private Map<String, Object> newCourse() {
        return Map.of("type", "NEW_COURSE", "topic", "자바", "level", "BEGINNER", "purpose", "WORK", "memo", "비공개 메모");
    }

    private LearningInput input() { return new LearningInput("자바", Level.BEGINNER, null, Purpose.WORK, null, null); }

    private String queued(long id) {
        return """
                {"data":{"id":%d,"type":"NEW_COURSE","status":"QUEUED", "subject":{"topic":"자바","level":"BEGINNER","purpose":"WORK"}}}
                """.formatted(id);
    }

    private String notFound() { return "{\"code\":\"NOT_FOUND\",\"message\":\"찾을 수 없습니다\"}"; }
}
