package com.mogum.course.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import com.mogum.account.domain.UserStatus;
import com.mogum.auth.AccountPrincipal;
import com.mogum.course.dto.HomeContinueLearningResponse;
import com.mogum.course.dto.HomeTodoResponse;
import com.mogum.course.service.HomeCourseReadService;

@WebMvcTest(HomeCourseController.class)
@AutoConfigureMockMvc(addFilters = false)
class HomeCourseControllerTest {
	@Autowired
	MockMvcTester mockMvcTester;
	@MockitoBean
	HomeCourseReadService homeCourseReadService;

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
	@DisplayName("이어서 배우기는 코스와 다음 강의의 ID 및 제목만 싣는다")
	void readsContinueLearning() {
		// given
		given(homeCourseReadService.continueLearning(7L)).willReturn(new HomeContinueLearningResponse(
			new HomeContinueLearningResponse.CourseItem(11L, "코스",
				new HomeContinueLearningResponse.LessonItem(12L, "강의"))));
		// when
		var result = mockMvcTester.get().uri("/api/home/continue-learning").exchange();
		// then
		assertThat(result).hasStatusOk().bodyJson().isStrictlyEqualTo(
			"{\"data\":{\"course\":{\"id\":11,\"title\":\"코스\",\"nextLesson\":{\"id\":12,\"title\":\"강의\"}}}}");
		then(homeCourseReadService).should().continueLearning(7L);
	}

	@Test
	@DisplayName("비어 있는 이어서 배우기는 빈 객체로 감싼다")
	void readsEmptyContinueLearning() {
		// given
		given(homeCourseReadService.continueLearning(7L)).willReturn(new HomeContinueLearningResponse(null));
		// when
		var result = mockMvcTester.get().uri("/api/home/continue-learning").exchange();
		// then
		assertThat(result).hasStatusOk().bodyJson().isStrictlyEqualTo("{\"data\":{}}");
	}

	@Test
	@DisplayName("할 일은 항목 종류와 대상 ID 및 제목을 목록으로 싣는다")
	void readsTodos() {
		// given
		given(homeCourseReadService.todos(7L)).willReturn(new HomeTodoResponse(List.of(
			new HomeTodoResponse.Item(HomeTodoResponse.Type.COURSE_REVIEW, 1L, "코스"),
			new HomeTodoResponse.Item(HomeTodoResponse.Type.ROADMAP_REVIEW, 2L, "계획"),
			new HomeTodoResponse.Item(HomeTodoResponse.Type.ROADMAP_NEXT_COURSE, 3L, "다음"))));
		// when
		var result = mockMvcTester.get().uri("/api/home/todos").exchange();
		// then
		assertThat(result).hasStatusOk().bodyJson().isStrictlyEqualTo("""
			{"data":{"items":[{"type":"COURSE_REVIEW","targetId":1,"title":"코스"},
			{"type":"ROADMAP_REVIEW","targetId":2,"title":"계획"},
			{"type":"ROADMAP_NEXT_COURSE","targetId":3,"title":"다음"}]}}
			""");
		then(homeCourseReadService).should().todos(7L);
	}

	@Test
	@DisplayName("할 일이 없으면 객체 안의 빈 목록을 반환한다")
	void readsEmptyTodos() {
		// given
		given(homeCourseReadService.todos(7L)).willReturn(new HomeTodoResponse(List.of()));
		// when
		var result = mockMvcTester.get().uri("/api/home/todos").exchange();
		// then
		assertThat(result).hasStatusOk().bodyJson().isStrictlyEqualTo("{\"data\":{\"items\":[]}}");
	}
}
