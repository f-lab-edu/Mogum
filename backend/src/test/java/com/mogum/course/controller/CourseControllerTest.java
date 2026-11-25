package com.mogum.course.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import com.mogum.account.domain.UserStatus;
import com.mogum.auth.AccountPrincipal;
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.course.domain.CourseStatus;
import com.mogum.course.domain.PlannedConcept;
import com.mogum.course.dto.CourseListResponse;
import com.mogum.course.dto.CourseResponse;
import com.mogum.course.service.CourseCommandService;
import com.mogum.course.service.CourseDeletionService;
import com.mogum.course.service.CourseReadService;

import tools.jackson.databind.json.JsonMapper;

@WebMvcTest(CourseController.class)
@AutoConfigureMockMvc(addFilters = false)
class CourseControllerTest {
	@Autowired
	MockMvcTester mockMvcTester;
	@MockitoBean
	CourseReadService courseReadService;
	@MockitoBean
	CourseCommandService courseCommandService;
	@MockitoBean
	CourseDeletionService courseDeletionService;
	private final JsonMapper jsonMapper = JsonMapper.builder().build();

	@BeforeEach
	void authenticate() {
		var context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
			new AccountPrincipal.Member(7L, "me@example.com", "나", UserStatus.APPROVED), null, List.of()));
		SecurityContextHolder.setContext(context);
	}

	@AfterEach
	void clearAuthentication() {
		SecurityContextHolder.clearContext();
	}

	@Test
	@DisplayName("코스 하나는 강의와 진도와 다음 강의를 한 번에 감싸 반환한다")
	void readsCourseShape() {
		// given
		given(courseReadService.read(7L, 1L)).willReturn(response());

		// when
		var result = mockMvcTester.get().uri("/api/courses/1").exchange();

		// then
		assertThat(result).hasStatusOk().bodyJson().isStrictlyEqualTo("""
			{"data":{"id":1,"status":"LEARNING","title":"코스","roadmapId":9,
			"lessons":[{"id":3,"position":1,"title":"강의","description":"설명",
			"concepts":[{"name":"개념","definition":"정의"}],"completed":false}],
			"progress":{"completedLessons":0,"totalLessons":1},"nextLessonId":3,"todayReviewCount":2}}
			""");
	}

	@ParameterizedTest(name = "짧게={0}, 다시={1}")
	@CsvSource({"2,1", "0,0"})
	@DisplayName("반영 내역은 이름 목록이나 판정 없이 두 정수만 싣고 영도 생략하지 않는다")
	void serializesOnlyReportCounts(int shortened, int revisited) {
		// given
		given(courseReadService.read(7L, 1L)).willReturn(new CourseResponse(1L, CourseStatus.REVIEWING,
			"코스", null, List.of(), new CourseResponse.Progress(0, 0), null, null, 0,
			new CourseResponse.MasteryReportCounts(shortened, revisited)));

		// when
		var result = mockMvcTester.get().uri("/api/courses/1").exchange();

		// then
		assertThat(result).hasStatusOk().bodyJson().isStrictlyEqualTo("""
			{"data":{"id":1,"status":"REVIEWING","title":"코스","lessons":[],
			"progress":{"completedLessons":0,"totalLessons":0},"todayReviewCount":0,
			"masteryReport":{"shortened":%d,"revisited":%d}}}
			""".formatted(shortened, revisited));
	}

	@Test
	@DisplayName("코스 목록은 이름 있는 목록 칸으로 감싼다")
	void readsListShape() {
		// given
		given(courseReadService.list(7L)).willReturn(new CourseListResponse(
			List.of(new CourseListResponse.Item(1L, "코스", CourseStatus.REVIEWING))));

		// when
		var result = mockMvcTester.get().uri("/api/courses").exchange();

		// then
		assertThat(result).hasStatusOk().bodyJson().isStrictlyEqualTo("""
			{"data":{"courses":[{"id":1,"title":"코스","status":"REVIEWING"}]}}
			""");
	}

	@Test
	@DisplayName("시작은 코스 하나와 같은 응답을 반환한다")
	void startsWithCourseShape() {
		// given
		given(courseCommandService.start(7L, 1L)).willReturn(response());

		// when
		var result = mockMvcTester.post().uri("/api/courses/1/start").exchange();

		// then
		assertThat(result).hasStatusOk().bodyJson().extractingPath("$.data.status").isEqualTo("LEARNING");
		assertThat(result).bodyJson().extractingPath("$.data.todayReviewCount").isEqualTo(2);
	}

	@ParameterizedTest(name = "제목={0}")
	@ValueSource(strings = {"", " \t\n", "LONG"})
	@DisplayName("빈 제목과 공백 및 101 코드 포인트 제목은 칸 오류로 답한다")
	void validatesTitle(String value) {
		// given
		String title = value.equals("LONG") ? "😀".repeat(101) : value;

		// when
		var result = mockMvcTester.patch().uri("/api/courses/1").contentType(MediaType.APPLICATION_JSON)
			.content(jsonMapper.writeValueAsString(Map.of("title", title))).exchange();

		// then
		assertThat(result).hasStatus(400).bodyJson().isStrictlyEqualTo("""
			{"code":"FIELD_INVALID","message":"입력을 확인해 주십시오",
			"fields":[{"field":"title","reason":"%s"}]}
			""".formatted(value.equals("LONG") ? "TOO_LONG" : "REQUIRED"));
		then(courseCommandService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("100 코드 포인트 제목과 모르는 칸은 받아 제목을 고친다")
	void renamesWithCodePointBoundary() {
		// given
		var title = "😀".repeat(100);
		given(courseCommandService.rename(7L, 1L, title)).willReturn(response());

		// when
		var result = mockMvcTester.patch().uri("/api/courses/1").contentType(MediaType.APPLICATION_JSON)
			.content(jsonMapper.writeValueAsString(Map.of("title", title, "future", true))).exchange();

		// then
		assertThat(result).hasStatusOk().bodyJson().extractingPath("$.data.todayReviewCount").isEqualTo(2);
		then(courseCommandService).should().rename(7L, 1L, title);
	}

	@Test
	@DisplayName("없는 코스는 공통 404 모양으로 답한다")
	void returnsNotFoundShape() {
		// given
		given(courseReadService.read(7L, 1L)).willThrow(new ApiException(ErrorCode.NOT_FOUND));

		// when
		var result = mockMvcTester.get().uri("/api/courses/1").exchange();

		// then
		assertThat(result).hasStatus(404).bodyJson().isStrictlyEqualTo(
			"{\"code\":\"NOT_FOUND\",\"message\":\"찾을 수 없습니다\"}");
	}

	@Test
	@DisplayName("코스 지우기는 로그인한 주인과 경로 ID를 넘기고 본문 없는 204로 답한다")
	void deletesWithoutBody() {
		// when
		var result = mockMvcTester.delete().uri("/api/courses/1").exchange();
		// then
		assertThat(result).hasStatus(204).body().isEmpty();
		then(courseDeletionService).should().delete(7L, 1L);
	}

	@ParameterizedTest(name = "오류={0}, 상태={1}")
	@CsvSource({"NOT_FOUND,404,찾을 수 없습니다",
		"COURSE_DELETE_NOT_ALLOWED,409,로드맵의 코스는 로드맵을 지워야 해요"})
	@DisplayName("없는 코스와 로드맵 코스 지우기는 공통 code와 문구로 답한다")
	void returnsDeletionErrors(ErrorCode code, int status, String message) {
		// given
		willThrow(new ApiException(code)).given(courseDeletionService).delete(7L, 1L);
		// when
		var result = mockMvcTester.delete().uri("/api/courses/1").exchange();
		// then
		assertThat(result).hasStatus(status).bodyJson().isStrictlyEqualTo(
			"{\"code\":\"%s\",\"message\":\"%s\"}".formatted(code, message));
	}

	private CourseResponse response() {
		return new CourseResponse(1L, CourseStatus.LEARNING, "코스", 9L,
			List.of(new CourseResponse.LessonItem(3L, 1, "강의", "설명",
				List.of(new PlannedConcept("개념", "정의")), false)),
			new CourseResponse.Progress(0, 1), 3L, null, 2, null);
	}
}
