package com.mogum.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import com.mogum.account.domain.UserStatus;
import com.mogum.account.service.WithdrawService;
import com.mogum.auth.AccountPrincipal;
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;

@WebMvcTest(WithdrawController.class)
@AutoConfigureMockMvc(addFilters = false)
class WithdrawControllerTest {
	@Autowired
	MockMvcTester mockMvcTester;
	@MockitoBean
	WithdrawService withdrawService;

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
	@DisplayName("탈퇴는 회원 ID를 넘기고 세션을 끝낸 뒤 본문 없는 204로 답한다")
	void withdrawsWithoutBodyAndInvalidatesSession() {
		// given
		var session = new MockHttpSession();
		// when
		var result = mockMvcTester.post().uri("/api/account/withdraw").session(session).exchange();
		// then
		assertThat(result).hasStatus(204).body().isEmpty();
		then(withdrawService).should().withdraw(7L);
		assertThat(session.isInvalid()).isTrue();
	}

	@Test
	@DisplayName("탈퇴 트랜잭션이 실패하면 현재 세션을 유지한다")
	void preservesSessionOnFailure() {
		// given
		var session = new MockHttpSession();
		willThrow(new ApiException(ErrorCode.NOT_FOUND)).given(withdrawService).withdraw(7L);
		// when
		var result = mockMvcTester.post().uri("/api/account/withdraw").session(session).exchange();
		// then
		assertThat(result).hasStatus(404);
		assertThat(session.isInvalid()).isFalse();
	}
}
