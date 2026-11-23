package com.mogum.course.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import com.mogum.account.domain.UserStatus;
import com.mogum.auth.AccountPrincipal;
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.course.dto.LessonCompletionResponse;
import com.mogum.course.dto.LessonResponse;
import com.mogum.course.service.LessonCommandService;
import com.mogum.course.service.LessonReadService;

@WebMvcTest(LessonController.class)
@AutoConfigureMockMvc(addFilters = false)
class LessonControllerTest {
	@Autowired
	MockMvcTester mockMvcTester;
	@MockitoBean
	LessonReadService lessonReadService;
	@MockitoBean
	LessonCommandService lessonCommandService;

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
	@DisplayName("강의 읽기는 본문과 완료 여부를 감싸고 빈 작업 칸은 생략한다")
	void readsMaterialShape() {
		// given
		given(lessonReadService.read(7L, 3L)).willReturn(new LessonResponse(3L, 1L, "코스", 2, "강의", true,
			new LessonResponse.MaterialBody("# 본문"), null));
		// when
		var result = mockMvcTester.get().uri("/api/lessons/3").exchange();
		// then
		assertThat(result).hasStatusOk().bodyJson().isStrictlyEqualTo("""
			{"data":{"id":3,"courseId":1,"courseTitle":"코스","position":2,"title":"강의",
			"completed":true,"material":{"body":"# 본문"}}}
			""");
	}

	@ParameterizedTest(name = "작업={0}")
	@ValueSource(strings = {"NONE", "QUEUED", "RUNNING", "FAILED", "SUCCEEDED"})
	@DisplayName("본문이 없으면 작업 ID와 상태만 싣고 둘 다 없으면 선택 칸을 생략한다")
	void readsJobOrEmptyShape(String status) {
		// given
		given(lessonReadService.read(7L, 3L)).willReturn(new LessonResponse(3L, 1L, "코스", 1, "강의", false,
			null, status.equals("NONE") ? null : new LessonResponse.MaterialJob(9L, status)));
		// when
		var result = mockMvcTester.get().uri("/api/lessons/3").exchange();
		// then
		String job = status.equals("NONE") ? "" : ",\"job\":{\"id\":9,\"status\":\"" + status + "\"}";
		assertThat(result).hasStatusOk().bodyJson().isStrictlyEqualTo(
			"{\"data\":{\"id\":3,\"courseId\":1,\"courseTitle\":\"코스\",\"position\":1,"
				+ "\"title\":\"강의\",\"completed\":false" + job + "}}");
	}

	@ParameterizedTest(name = "코스 완료={0}")
	@ValueSource(booleans = {true, false})
	@DisplayName("학습 완료는 본문 없이 받고 코스 ID와 이번에 다 마쳤는지를 반환한다")
	void completesLessonShape(boolean completed) {
		// given
		given(lessonCommandService.complete(7L, 3L)).willReturn(new LessonCompletionResponse(1L, completed));
		// when
		var result = mockMvcTester.post().uri("/api/lessons/3/complete").exchange();
		// then
		assertThat(result).hasStatusOk().bodyJson().isStrictlyEqualTo(
			"{\"data\":{\"courseId\":1,\"courseCompleted\":" + completed + "}}");
	}

	@ParameterizedTest(name = "오류={0}")
	@ValueSource(strings = {"NOT_FOUND", "LESSON_NOT_OPEN", "LESSON_MATERIAL_REQUIRED"})
	@DisplayName("학습 완료의 거절은 공통 오류 모양으로 반환한다")
	void returnsCompletionErrors(String name) {
		// given
		var code = ErrorCode.valueOf(name);
		given(lessonCommandService.complete(7L, 3L)).willThrow(new ApiException(code));
		// when
		var result = mockMvcTester.post().uri("/api/lessons/3/complete").exchange();
		// then
		assertThat(result).hasStatus(code.status().value()).bodyJson().isStrictlyEqualTo(
			"{\"code\":\"" + name + "\",\"message\":\"" + code.message() + "\"}");
	}

	@ParameterizedTest(name = "오류={0}")
	@ValueSource(strings = {"NOT_FOUND", "LESSON_NOT_OPEN"})
	@DisplayName("강의 읽기의 거절은 공통 오류 모양으로 반환한다")
	void returnsReadErrors(String name) {
		// given
		var code = ErrorCode.valueOf(name);
		given(lessonReadService.read(7L, 3L)).willThrow(new ApiException(code));
		// when
		var result = mockMvcTester.get().uri("/api/lessons/3").exchange();
		// then
		assertThat(result).hasStatus(code.status().value()).bodyJson().isStrictlyEqualTo(
			"{\"code\":\"" + name + "\",\"message\":\"" + code.message() + "\"}");
	}
}
