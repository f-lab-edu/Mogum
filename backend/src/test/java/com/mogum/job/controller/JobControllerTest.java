package com.mogum.job.controller;

import com.mogum.account.domain.UserStatus;
import com.mogum.auth.AccountPrincipal;
import com.mogum.job.JobRequest;
import com.mogum.job.domain.JobStatus;
import com.mogum.job.domain.JobType;
import com.mogum.job.dto.JobResponse;
import com.mogum.job.service.JobIntakeService;
import com.mogum.job.service.JobReadService;
import com.mogum.job.service.JobResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@WebMvcTest(JobController.class)
@AutoConfigureMockMvc(addFilters = false)
class JobControllerTest {
    @Autowired MockMvcTester mockMvcTester;
    @MockitoBean JobIntakeService jobIntakeService;
    @MockitoBean JobReadService jobReadService;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @BeforeEach
    void authenticate() {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new AccountPrincipal.Member(7L, "me@example.com", "나", UserStatus.APPROVED), null, List.of()));
        SecurityContextHolder.setContext(context);
    }

    @AfterEach
    void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    @DisplayName("잘못된 요청은 서비스 전에 칸과 reason으로 반환한다")
    void rejectsInvalidFields(String description, Map<String, Object> body, String field, String reason) {
        // when
        var result = mockMvcTester.post().uri("/api/jobs").contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body)).exchange();

        // then
        assertThat(result).hasStatus(400).bodyJson().isStrictlyEqualTo("""
                {"code":"FIELD_INVALID","message":"입력을 확인해 주십시오",
                 "fields":[{"field":"%s","reason":"%s"}]}
                """.formatted(field, reason));
        then(jobIntakeService).shouldHaveNoInteractions();
        then(jobReadService).shouldHaveNoInteractions();
    }

    static Stream<Object[]> invalidRequests() {
        var cases = new ArrayList<Object[]>();
        for (var field : List.of("topic", "level", "purpose", "type")) {
            cases.add(new Object[]{field + " 누락", changed(field, null), field, "REQUIRED"});
            cases.add(new Object[]{field + " 빈 글", changed(field, " \t\n"), field, "REQUIRED"});
        }
        for (var field : List.of("type", "level", "purpose"))
            cases.add(new Object[]{field + " 모르는 값", changed(field, "FUTURE"), field, "INVALID_VALUE"});
        for (var field : List.of("topic", "memo", "levelOther", "purposeOther", "feedback")) {
            var body = changed(field, "가".repeat(field.equals("topic") ? 101 : field.endsWith("Other") ? 51 : 501));
            if (field.equals("levelOther")) body.put("level", "OTHER");
            if (field.equals("purposeOther")) body.put("purpose", "OTHER");
            if (field.equals("feedback")) body = target("REPLAN_COURSE", "courseId", 1L, field, body.get(field));
            cases.add(new Object[]{field + " 상한 초과", body, field, "TOO_LONG"});
        }
        for (var field : List.of("memo", "levelOther", "purposeOther", "feedback")) {
            var body = changed(field, "\t\n ");
            if (field.equals("levelOther")) body.put("level", "OTHER");
            if (field.equals("purposeOther")) body.put("purpose", "OTHER");
            if (field.equals("feedback")) body = target("REPLAN_COURSE", "courseId", 1L, field, "\t\n ");
            cases.add(new Object[]{field + " 빈 글", body, field, "REQUIRED"});
        }
        cases.add(new Object[]{"기타 수준 글 누락", changed("level", "OTHER"), "levelOther", "REQUIRED"});
        cases.add(new Object[]{"기타 목적 글 누락", changed("purpose", "OTHER"), "purposeOther", "REQUIRED"});
        for (var field : List.of("levelOther", "purposeOther", "feedback", "courseId", "roadmapId", "lessonId"))
            cases.add(new Object[]{field + " 허용 안 됨", changed(field, field.endsWith("Id") ? 1L : "글"), field, "NOT_ALLOWED"});
        for (var entry : Map.of("MATERIAL", "lessonId", "PLAN_ROADMAP_COURSE", "courseId",
                "REPLAN_COURSE", "courseId", "REPLAN_ROADMAP", "roadmapId").entrySet()) {
            cases.add(new Object[]{entry.getKey() + " 대상 누락", Map.of("type", entry.getKey()), entry.getValue(), "REQUIRED"});
            for (var field : List.of("topic", "level", "purpose", "levelOther", "purposeOther", "memo", "courseId", "roadmapId", "lessonId")) {
                if (field.equals(entry.getValue())) continue;
                cases.add(new Object[]{entry.getKey() + " " + field + " 허용 안 됨",
                        target(entry.getKey(), entry.getValue(), 1L, field, field.endsWith("Id") ? 2L : "글"), field, "NOT_ALLOWED"});
            }
        }
        cases.add(new Object[]{"자료에 피드백 불가", target("MATERIAL", "lessonId", 1L, "feedback", "글"), "feedback", "NOT_ALLOWED"});
        cases.add(new Object[]{"첫 코스에 피드백 불가", target("PLAN_ROADMAP_COURSE", "courseId", 1L, "feedback", "글"), "feedback", "NOT_ALLOWED"});
        return cases.stream();
    }

    @Test
    @DisplayName("받기는 모르는 칸을 무시하고 입력과 메모를 서비스에 넘기며 읽기와 같은 응답을 낸다")
    void receivesAndReturnsReadShape() {
        // given
        given(jobIntakeService.receive(eq(7L), any())).willReturn(new JobResult(11L, JobStatus.QUEUED));
        given(jobReadService.read(7L, 11L)).willReturn(response());
        var body = changed("memo", "비공개 메모");
        body.put("future", Map.of("value", 1));

        // when
        var result = mockMvcTester.post().uri("/api/jobs").contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body)).exchange();

        // then
        assertReadShape(result);
        var captor = ArgumentCaptor.forClass(JobRequest.class);
        then(jobIntakeService).should().receive(eq(7L), captor.capture());
        var request = captor.getValue();
        assertThat(request.type()).isEqualTo(JobType.NEW_COURSE);
        assertThat(request.targetId()).isNull();
        assertThat(request.input().getMemo()).isEqualTo("비공개 메모");
        assertThat(request.input().getTopic()).isEqualTo("자바");
    }

    @ParameterizedTest(name = "종류={0}")
    @EnumSource(JobType.class)
    @DisplayName("여섯 종류를 각각 입력 또는 대상과 선택 피드백으로 변환한다")
    void mapsEveryJobType(JobType type) {
        // given
        given(jobIntakeService.receive(eq(7L), any())).willReturn(new JobResult(11L, JobStatus.QUEUED));
        given(jobReadService.read(7L, 11L)).willReturn(response());
        Map<String, Object> body = switch (type) {
            case NEW_COURSE, NEW_ROADMAP -> changed("type", type.name());
            case PLAN_ROADMAP_COURSE, REPLAN_COURSE -> target(type.name(), "courseId", 23L, "feedback", null);
            case REPLAN_ROADMAP -> target(type.name(), "roadmapId", 23L, "feedback", null);
            case MATERIAL -> target(type.name(), "lessonId", 23L, "feedback", null);
        };
        if (type.name().startsWith("REPLAN")) body.put("feedback", "바꿔 주세요");

        // when
        var result = mockMvcTester.post().uri("/api/jobs").contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body)).exchange();

        // then
        assertThat(result).hasStatusOk();
        var captor = ArgumentCaptor.forClass(JobRequest.class);
        then(jobIntakeService).should().receive(eq(7L), captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(type);
        assertThat(captor.getValue().targetId()).isEqualTo(type.name().startsWith("NEW") ? null : 23L);
        assertThat(captor.getValue().feedback()).isEqualTo(type.name().startsWith("REPLAN") ? "바꿔 주세요" : null);
    }

    @Test
    @DisplayName("읽기는 주인 ID와 작업 ID를 넘기고 입력 요약만 반환한다")
    void readsJob() {
        // given
        given(jobReadService.read(7L, 11L)).willReturn(response());

        // when
        var result = mockMvcTester.get().uri("/api/jobs/11").exchange();

        // then
        assertReadShape(result);
        then(jobIntakeService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("다시 시도도 작업 읽기와 같은 응답을 반환한다")
    void retriesJob() {
        // given
        given(jobIntakeService.retry(7L, 11L)).willReturn(new JobResult(11L, JobStatus.QUEUED));
        given(jobReadService.read(7L, 11L)).willReturn(response());

        // when
        var result = mockMvcTester.post().uri("/api/jobs/11/retry").exchange();

        // then
        assertReadShape(result);
    }

    @Test
    @DisplayName("실패 버리기는 POST 뒤 본문 없는 204를 반환한다")
    void discardsJob() {
        // when
        var result = mockMvcTester.post().uri("/api/jobs/11/discard").exchange();

        // then
        assertThat(result).hasStatus(204).body().isEmpty();
        then(jobIntakeService).should().discard(7L, 11L);
        then(jobReadService).shouldHaveNoInteractions();
    }

    private JobResponse response() {
        return new JobResponse(11L, JobType.NEW_COURSE, JobStatus.QUEUED,
                new JobResponse.Subject("자바", "BEGINNER", null, "WORK", null, null), null, null);
    }

    private void assertReadShape(MvcTestResult result) {
        assertThat(result).hasStatusOk().bodyJson().isStrictlyEqualTo("""
                {"data":{"id":11,"type":"NEW_COURSE","status":"QUEUED",
                 "subject":{"topic":"자바","level":"BEGINNER","purpose":"WORK"}}}
                """);
    }

    private static Map<String, Object> changed(String field, Object value) {
        var body = new LinkedHashMap<String, Object>(Map.of("type", "NEW_COURSE", "topic", "자바", "level", "BEGINNER", "purpose", "WORK"));
        body.put(field, value);
        return body;
    }

    private static Map<String, Object> target(String type, String field, Object id, String extra, Object value) {
        var body = new LinkedHashMap<String, Object>();
        body.put("type", type);
        body.put(field, id);
        body.put(extra, value);
        return body;
    }
}
