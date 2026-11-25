package com.mogum.job.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.domain.UserStatus;
import com.mogum.account.repository.UserRepository;
import com.mogum.auth.AccountPrincipal;
import com.mogum.course.domain.LearningInput;
import com.mogum.course.domain.Level;
import com.mogum.course.domain.Purpose;
import com.mogum.job.domain.GenerationJobFixtures;
import com.mogum.job.repository.GenerationJobRepository;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestRequests;
import com.mogum.support.TestSessions;

import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class HomeJobControllerIntegrationTest {
	@Autowired
	TestRequests testRequests;
	@Autowired
	UserRepository userRepository;
	@Autowired
	GenerationJobRepository generationJobRepository;
	@Autowired
	JdbcIndexedSessionRepository jdbcIndexedSessionRepository;
	private final JsonMapper jsonMapper = JsonMapper.builder().build();

	@Test
	@DisplayName("홈 작업은 실제 로그인 회원의 작업만 작업 하나 읽기와 같은 응답으로 반환한다")
	void returnsOwnedJobMatchingSingleReadOverHttp() {
		// given
		long owner = userRepository.save(UserFixtures.approved("me@example.com")).getId();
		long other = userRepository.save(UserFixtures.approved("other@example.com")).getId();
		var input = new LearningInput("자바", Level.BEGINNER, null, Purpose.WORK, null, "비공개 메모");
		var job = generationJobRepository.save(GenerationJobFixtures.newCourse(owner, input));
		generationJobRepository.save(GenerationJobFixtures.newCourse(other, input));
		var client = testRequests.asUser(owner);
		var single = client.get().uri("/api/jobs/" + job.getId()).exchange()
			.expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();

		// when
		var response = client.get().uri("/api/home/jobs").exchange();

		// then
		var body = response.expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
		var jobs = jsonMapper.readTree(body).path("data").path("jobs");
		assertThat(jobs.size()).isEqualTo(1);
		assertThat(jobs.get(0)).isEqualTo(jsonMapper.readTree(single).path("data"));
	}

	@Test
	@DisplayName("로그인하지 않으면 홈 작업을 읽을 수 없다")
	void rejectsAnonymousRead() {
		// when
		var response = testRequests.anonymous().get().uri("/api/home/jobs").exchange();

		// then
		response.expectStatus().isUnauthorized();
	}

	@ParameterizedTest(name = "상태={0}")
	@EnumSource(value = UserStatus.class, names = {"APPLIED", "REJECTED"})
	@DisplayName("신청 또는 거절 계정은 홈 작업을 읽을 수 없다")
	void rejectsUnapprovedRead(UserStatus status) {
		// given
		long user = userRepository.save(UserFixtures.withStatus(UserFixtures.user("me@example.com"), status)).getId();
		var client = testRequests.asUser(user);

		// when
		var response = client.get().uri("/api/home/jobs").exchange();

		// then
		response.expectStatus().isForbidden().expectBody().jsonPath("$.code").isEqualTo("ACCOUNT_NOT_APPROVED");
	}

	@Test
	@DisplayName("신청 전 신원 세션은 홈 작업을 읽을 수 없다")
	void rejectsNotAppliedRead() {
		// given
		var cookie = new TestSessions(jdbcIndexedSessionRepository).createNotApplied(
			new AccountPrincipal.NotApplied("google", "sub", "me@example.com", "나"));
		var client = testRequests.anonymous().mutate().defaultCookie(cookie.getName(), cookie.getValue()).build();

		// when
		var response = client.get().uri("/api/home/jobs").exchange();

		// then
		response.expectStatus().isForbidden().expectBody().jsonPath("$.code").isEqualTo("ACCOUNT_NOT_APPROVED");
	}
}
