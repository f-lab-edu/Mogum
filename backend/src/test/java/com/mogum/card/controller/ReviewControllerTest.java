package com.mogum.card.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.util.List;
import java.util.Map;

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
import com.mogum.card.domain.Rating;
import com.mogum.card.dto.TodayReviewResponse;
import com.mogum.card.service.TodayReviewService;
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;

@WebMvcTest(ReviewController.class)
@AutoConfigureMockMvc(addFilters = false)
class ReviewControllerTest {
	@Autowired
	MockMvcTester mockMvcTester;
	@MockitoBean
	TodayReviewService todayReviewService;

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

	@ParameterizedTest(name = "코스로 거르기={0}")
	@ValueSource(booleans = {false, true})
	@DisplayName("오늘 복습 읽기는 카드와 초 단위 간격 및 남은 수만 싣고 코스로 거르면 제목을 더한다")
	void readsTodayShape(boolean filtered) {
		// given
		Long courseId = filtered ? 3L : null;
		given(todayReviewService.read(7L, courseId)).willReturn(new TodayReviewResponse(
			List.of(new TodayReviewResponse.CardItem(9L, "질문", "답",
				Map.of(Rating.AGAIN, 60L, Rating.HARD, 330L, Rating.GOOD, 600L, Rating.EASY, 259200L))),
			1, filtered ? "코스" : null));
		// when
		var result = mockMvcTester.get().uri(filtered ? "/api/reviews/today?courseId=3" : "/api/reviews/today")
			.exchange();
		// then
		String title = filtered ? ",\"courseTitle\":\"코스\"" : "";
		assertThat(result).hasStatusOk().bodyJson().isStrictlyEqualTo("""
			{"data":{"cards":[{"id":9,"question":"질문","answer":"답",
			"nextIntervalsSeconds":{"AGAIN":60,"HARD":330,"GOOD":600,"EASY":259200}}],"remainingCount":1%s}}
			""".formatted(title));
	}

	@Test
	@DisplayName("볼 카드가 없으면 빈 목록과 0장을 돌려준다")
	void returnsEmptyList() {
		// given
		given(todayReviewService.read(7L, null)).willReturn(new TodayReviewResponse(List.of(), 0, null));
		// when
		var result = mockMvcTester.get().uri("/api/reviews/today").exchange();
		// then
		assertThat(result).hasStatusOk().bodyJson().isStrictlyEqualTo(
			"{\"data\":{\"cards\":[],\"remainingCount\":0}}");
	}

	@Test
	@DisplayName("남의 코스로 거른 복습은 공통 없는 것 오류로 답한다")
	void hidesForeignCourse() {
		// given
		given(todayReviewService.read(7L, 3L)).willThrow(new ApiException(ErrorCode.NOT_FOUND));
		// when
		var result = mockMvcTester.get().uri("/api/reviews/today?courseId=3").exchange();
		// then
		assertThat(result).hasStatus(404).bodyJson().isStrictlyEqualTo(
			"{\"code\":\"NOT_FOUND\",\"message\":\"찾을 수 없습니다\"}");
	}

	@Test
	@DisplayName("코스 ID가 숫자가 아니면 공통 잘못된 요청 오류로 답한다")
	void rejectsMalformedCourseId() {
		// when
		var result = mockMvcTester.get().uri("/api/reviews/today?courseId=bad").exchange();
		// then
		assertThat(result).hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("INVALID_REQUEST");
	}
}
