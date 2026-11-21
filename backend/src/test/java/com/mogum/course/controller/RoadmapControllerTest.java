package com.mogum.course.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

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
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.course.domain.CourseStatus;
import com.mogum.course.domain.RoadmapStatus;
import com.mogum.course.dto.CourseProgress;
import com.mogum.course.dto.RoadmapListResponse;
import com.mogum.course.dto.RoadmapResponse;
import com.mogum.course.service.RoadmapReadService;

@WebMvcTest(RoadmapController.class)
@AutoConfigureMockMvc(addFilters = false)
class RoadmapControllerTest {
	@Autowired
	MockMvcTester mockMvcTester;
	@MockitoBean
	RoadmapReadService roadmapReadService;

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
	@DisplayName("로드맵 하나는 계획과 코스 진행과 다음 코스를 한 객체로 답한다")
	void readsRoadmapShape() {
		// given
		given(roadmapReadService.read(7L, 1L)).willReturn(new RoadmapResponse(1L, RoadmapStatus.IN_PROGRESS,
			"로드맵", "설명", List.of(new RoadmapResponse.CourseItem(2L, 1, "첫", "계획", List.of(),
				CourseStatus.NOT_PLANNED, CourseProgress.GENERATING, 3L)),
			null));

		// when
		var result = mockMvcTester.get().uri("/api/roadmaps/1").exchange();

		// then
		assertThat(result).hasStatusOk().bodyJson().isStrictlyEqualTo("""
			{"data":{"id":1,"status":"IN_PROGRESS","title":"로드맵","description":"설명",
			"courses":[{"id":2,"position":1,"title":"첫","description":"계획","concepts":[],
			"status":"NOT_PLANNED","progress":"GENERATING","jobId":3}]}}
			""");
	}

	@Test
	@DisplayName("로드맵 목록은 설명과 완료 코스 수와 전체 코스 수를 담는다")
	void readsListShape() {
		// given
		given(roadmapReadService.list(7L)).willReturn(new RoadmapListResponse(List.of(
			new RoadmapListResponse.Item(1L, "로드맵", "설명", RoadmapStatus.IN_PROGRESS, 1, 3))));

		// when
		var result = mockMvcTester.get().uri("/api/roadmaps").exchange();

		// then
		assertThat(result).hasStatusOk().bodyJson().isStrictlyEqualTo("""
			{"data":{"roadmaps":[{"id":1,"title":"로드맵","description":"설명",
			"status":"IN_PROGRESS","completedCourses":1,"totalCourses":3}]}}
			""");
	}

	@Test
	@DisplayName("없는 로드맵은 공통 404로 답한다")
	void returnsNotFoundShape() {
		// given
		given(roadmapReadService.read(7L, 1L)).willThrow(new ApiException(ErrorCode.NOT_FOUND));

		// when
		var result = mockMvcTester.get().uri("/api/roadmaps/1").exchange();

		// then
		assertThat(result).hasStatus(404).bodyJson().isStrictlyEqualTo(
			"{\"code\":\"NOT_FOUND\",\"message\":\"찾을 수 없습니다\"}");
	}
}
