package com.mogum.card.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import com.mogum.card.dto.StreakResponse;
import com.mogum.card.service.StreakReadService;
import com.mogum.card.service.TodayReviewService;

@WebMvcTest(HomeReviewController.class)
@AutoConfigureMockMvc(addFilters = false)
class HomeReviewControllerTest {
	@Autowired
	MockMvcTester mockMvcTester;
	@MockitoBean
	TodayReviewService todayReviewService;
	@MockitoBean
	StreakReadService streakReadService;

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

	@ParameterizedTest(name = "오늘 복습 수={0}")
	@ValueSource(ints = {0, 3})
	@DisplayName("홈 오늘 복습은 코스로 거르지 않은 수만 싣고 스트릭을 읽지 않는다")
	void readsOnlyUnfilteredCount(int count) {
		// given
		given(todayReviewService.count(7L, null)).willReturn(count);
		// when
		var result = mockMvcTester.get().uri("/api/home/reviews").exchange();
		// then
		assertThat(result).hasStatusOk().bodyJson().isStrictlyEqualTo(
			"{\"data\":{\"remainingCount\":%d}}".formatted(count));
		then(todayReviewService).should().count(7L, null);
		then(streakReadService).shouldHaveNoInteractions();
	}

	@ParameterizedTest(name = "연속 일수={0}, 오늘 채움={1}")
	@CsvSource({"0,false", "2,false", "3,true"})
	@DisplayName("홈 스트릭은 연속 일수와 오늘 채웠는지만 싣고 오늘 복습을 읽지 않는다")
	void readsOnlyStreak(int days, boolean filledToday) {
		// given
		given(streakReadService.read(7L)).willReturn(new StreakResponse(days, filledToday));
		// when
		var result = mockMvcTester.get().uri("/api/home/streak").exchange();
		// then
		assertThat(result).hasStatusOk().bodyJson().isStrictlyEqualTo(
			"{\"data\":{\"days\":%d,\"filledToday\":%s}}".formatted(days, filledToday));
		then(todayReviewService).shouldHaveNoInteractions();
	}
}
