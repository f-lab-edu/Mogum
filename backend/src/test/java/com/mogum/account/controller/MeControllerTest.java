package com.mogum.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.domain.UserStatus;
import com.mogum.account.dto.MeResponse;
import com.mogum.account.repository.UserRepository;
import com.mogum.account.service.MeReadService;
import com.mogum.auth.AccountPrincipal;
import com.mogum.auth.SecurityConfiguration;

import jakarta.persistence.EntityManagerFactory;

@WebMvcTest(MeController.class)
@AutoConfigureMockMvc(addFilters = false)
class MeControllerTest {
	@Autowired
	private MockMvcTester mockMvcTester;
	@Autowired
	private ApplicationContext applicationContext;

	@MockitoBean
	private MeReadService meReadService;

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@ParameterizedTest(name = "회원 상태={0}")
	@EnumSource(value = UserStatus.class, names = {"APPLIED", "REJECTED"})
	@DisplayName("승인 전 회원의 상태와 이메일과 이름만 반환하고 크레딧 칸은 생략한다")
	void returnsStatusEmailAndName(UserStatus status) {
		// given
		var user = UserFixtures.withId(UserFixtures.user("google", "sub", "me@example.com"), 7L);
		UserFixtures.withStatus(user, status);

		// 필터를 거치지 않으므로 인자 해석기가 읽는 현재 스레드의 인증에 회원을 넣는다.
		var principal = new AccountPrincipal.Member(user.getId(), user.getEmail(), user.getName(), user.getStatus());
		given(meReadService.read(principal))
			.willReturn(new MeResponse(status.name(), user.getEmail(), user.getName(), null));
		var securityContext = SecurityContextHolder.createEmptyContext();
		securityContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
			principal, null, List.of()));
		SecurityContextHolder.setContext(securityContext);

		// when
		var result = mockMvcTester.get().uri("/api/me").exchange();

		// then
		assertThat(result).hasStatusOk();
		assertThat(result).bodyJson().isStrictlyEqualTo("""
			{"data":{"status":"%s","email":"me@example.com","name":"me"}}
			""".formatted(status));
		assertThat(applicationContext.getBeansOfType(DataSource.class)).isEmpty();
		assertThat(applicationContext.getBeansOfType(EntityManagerFactory.class)).isEmpty();
		assertThat(applicationContext.getBeansOfType(UserRepository.class)).isEmpty();
		assertThat(applicationContext.getBeansOfType(SecurityConfiguration.class)).isEmpty();
	}

	@Test
	@DisplayName("신청 전 사람은 세션 신원으로 상태와 이메일과 이름만 반환한다")
	void returnsNotAppliedIdentity() {
		// given
		var identity = new AccountPrincipal.NotApplied("google", "sub", "new@example.com", "구글 이름");
		given(meReadService.read(identity))
			.willReturn(new MeResponse("NOT_APPLIED", identity.email(), identity.name(), null));
		var securityContext = SecurityContextHolder.createEmptyContext();
		securityContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(identity, null, List.of()));
		SecurityContextHolder.setContext(securityContext);

		// when
		var result = mockMvcTester.get().uri("/api/me").exchange();

		// then
		assertThat(result).hasStatusOk().bodyJson().isStrictlyEqualTo("""
			{"data":{"status":"NOT_APPLIED","email":"new@example.com","name":"구글 이름"}}
			""");
	}

	@Test
	@DisplayName("승인된 회원은 정수 잔액과 서울 날짜 문자열을 크레딧 객체로 반환한다")
	void returnsApprovedCreditObject() {
		// given
		var principal = new AccountPrincipal.Member(7L, "me@example.com", "me", UserStatus.APPROVED);
		given(meReadService.read(principal)).willReturn(new MeResponse("APPROVED", principal.email(),
			principal.name(), new MeResponse.Credit(17, "2026-12-08")));
		var context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
		SecurityContextHolder.setContext(context);

		// when
		var result = mockMvcTester.get().uri("/api/me").exchange();

		// then
		assertThat(result).hasStatusOk().bodyJson().isStrictlyEqualTo("""
			{"data":{"status":"APPROVED","email":"me@example.com","name":"me",
			"credit":{"balance":17,"nextRefillDate":"2026-12-08"}}}
			""");
		assertThat(result).bodyJson().extractingPath("$.data.credit.balance").isEqualTo(17);
		assertThat(result).bodyJson().extractingPath("$.data.credit.nextRefillDate").asString()
			.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}");
	}
}
