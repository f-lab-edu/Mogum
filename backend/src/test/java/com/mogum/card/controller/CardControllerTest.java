package com.mogum.card.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
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
import com.mogum.card.domain.Rating;
import com.mogum.card.service.CardCommandService;
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;

@WebMvcTest(CardController.class)
@AutoConfigureMockMvc(addFilters = false)
class CardControllerTest {

	@Autowired
	MockMvcTester mockMvcTester;
	@MockitoBean
	CardCommandService cardCommandService;

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

	@ParameterizedTest(name = "평가={0}")
	@EnumSource(Rating.class)
	@DisplayName("네 등급은 그대로 서비스에 넘기고 평가는 본문 없는 성공을 반환한다")
	void recordsRatingWithoutResponseBody(Rating rating) {
		// when
		var result = mockMvcTester.post().uri("/api/cards/9/reviews").contentType(MediaType.APPLICATION_JSON)
			.content("{\"rating\":\"" + rating + "\",\"unknown\":true}").exchange();
		// then
		assertThat(result).hasStatus(204).body().isEmpty();
		then(cardCommandService).should().review(7L, 9L, rating);
	}

	@ParameterizedTest(name = "본문={0}, reason={1}")
	@CsvSource(value = {"{}|REQUIRED", "{\"rating\":null}|REQUIRED",
		"{\"rating\":\"UNKNOWN\"}|INVALID_VALUE", "{\"rating\":\"good\"}|INVALID_VALUE",
		"{\"rating\":\"\"}|INVALID_VALUE"}, delimiter = '|')
	@DisplayName("빠진 평가와 모르는 평가는 공통 칸 오류로 반환한다")
	void rejectsInvalidRating(String body, String reason) {
		// when
		var result = mockMvcTester.post().uri("/api/cards/9/reviews").contentType(MediaType.APPLICATION_JSON)
			.content(body).exchange();
		// then
		assertThat(result).hasStatus(400).bodyJson().isStrictlyEqualTo("""
			{"code":"FIELD_INVALID","message":"입력을 확인해 주십시오",
			"fields":[{"field":"rating","reason":"%s"}]}
			""".formatted(reason));
		then(cardCommandService).shouldHaveNoInteractions();
	}

	@ParameterizedTest(name = "본문={0}")
	@ValueSource(strings = {"", "{"})
	@DisplayName("없는 본문과 깨진 JSON은 잘못된 요청으로 반환한다")
	void rejectsMalformedBody(String body) {
		// when
		var result = mockMvcTester.post().uri("/api/cards/9/reviews").contentType(MediaType.APPLICATION_JSON)
			.content(body).exchange();
		// then
		assertThat(result).hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("INVALID_REQUEST");
		then(cardCommandService).shouldHaveNoInteractions();
	}

	@ParameterizedTest(name = "오류={0}")
	@EnumSource(value = ErrorCode.class, names = {"NOT_FOUND", "CARD_REVIEW_NOT_ALLOWED"})
	@DisplayName("평가 거절은 공통 오류 경로의 상태와 문구를 따른다")
	void returnsReviewRejection(ErrorCode code) {
		// given
		willThrow(new ApiException(code)).given(cardCommandService).review(7L, 9L, Rating.GOOD);
		// when
		var result = mockMvcTester.post().uri("/api/cards/9/reviews").contentType(MediaType.APPLICATION_JSON)
			.content("{\"rating\":\"GOOD\"}").exchange();
		// then
		assertThat(result).hasStatus(code.status().value()).bodyJson().isStrictlyEqualTo(
			"{\"code\":\"" + code + "\",\"message\":\"" + code.message() + "\"}");
	}

	@ParameterizedTest(name = "없는 카드={0}")
	@ValueSource(booleans = {false, true})
	@DisplayName("지우기는 본문 없는 성공 또는 없는 카드 오류를 반환한다")
	void deletesCard(boolean missing) {
		// given
		if (missing) {
			willThrow(new ApiException(ErrorCode.NOT_FOUND)).given(cardCommandService).delete(7L, 9L);
		}
		// when
		var result = mockMvcTester.delete().uri("/api/cards/9").exchange();
		// then
		if (missing) {
			assertThat(result).hasStatus(404).bodyJson().isStrictlyEqualTo(
				"{\"code\":\"NOT_FOUND\",\"message\":\"찾을 수 없습니다\"}");
		} else {
			assertThat(result).hasStatus(204).body().isEmpty();
		}
		then(cardCommandService).should().delete(7L, 9L);
	}
}
