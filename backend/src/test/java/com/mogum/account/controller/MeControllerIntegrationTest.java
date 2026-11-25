package com.mogum.account.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.json.JsonCompareMode;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.account.service.CreditLedger;
import com.mogum.course.domain.LearningInput;
import com.mogum.course.domain.Level;
import com.mogum.course.domain.Purpose;
import com.mogum.job.domain.GenerationJobFixtures;
import com.mogum.job.repository.GenerationJobRepository;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestRequests;

@IntegrationTest
class MeControllerIntegrationTest {
	@Autowired
	TestRequests testRequests;
	@Autowired
	UserRepository userRepository;
	@Autowired
	JdbcTemplate jdbcTemplate;
	@Autowired
	CreditLedger creditLedger;
	@Autowired
	GenerationJobRepository generationJobRepository;

	@ParameterizedTest(name = "잔액 {0}, 채움 시각 {1}, 서울 날짜 {2}")
	@CsvSource({
		"0,2026-12-07T19:00:00Z,2026-12-08",
		"17,2026-12-31T19:00:00Z,2027-01-01",
		"9,2026-12-07T18:59:59Z,2026-12-08"
	})
	@DisplayName("승인된 사람은 DB 잔액과 서울 달력 날짜만 읽고 사용자와 기록을 바꾸지 않는다")
	void readsStoredCreditWithoutMutation(int balance, String refillAt, String date) {
		// given
		var user = userRepository.save(UserFixtures.approved("me@example.com", balance, Instant.parse(refillAt)));
		var client = testRequests.asUser(user.getId());
		var before = jdbcTemplate.queryForMap("SELECT * FROM users WHERE id = ?", user.getId());
		var entries = jdbcTemplate.queryForList("SELECT * FROM credit_entries");

		// when
		var response = client.get().uri("/api/me").exchange();

		// then
		response.expectStatus().isOk().expectBody().json("""
			{"data":{"status":"APPROVED","email":"me@example.com","name":"me",
			"credit":{"balance":%d,"nextRefillDate":"%s"}}}
			""".formatted(balance, date), JsonCompareMode.STRICT);
		assertThat(jdbcTemplate.queryForMap("SELECT * FROM users WHERE id = ?", user.getId())).isEqualTo(before);
		assertThat(jdbcTemplate.queryForList("SELECT * FROM credit_entries")).isEqualTo(entries);
	}

	@Test
	@DisplayName("같은 세션으로 크레딧을 쓴 뒤 다시 읽으면 새 잔액을 반환한다")
	void reloadsBalanceAfterConsumption() {
		// given
		var user = userRepository.save(
			UserFixtures.approved("me@example.com", 17, Instant.parse("2026-12-07T19:00:00Z")));
		var client = testRequests.asUser(user.getId());
		client.get().uri("/api/me").exchange().expectBody().jsonPath("$.data.credit.balance").isEqualTo(17);
		var input = new LearningInput("자바", Level.BEGINNER, null, Purpose.WORK, null, null);
		var job = generationJobRepository.save(GenerationJobFixtures.newCourse(user.getId(), input));
		creditLedger.consume(user.getId(), job.getId(), 5);

		// when
		var response = client.get().uri("/api/me").exchange();

		// then
		response.expectStatus().isOk().expectBody().jsonPath("$.data.credit.balance").isEqualTo(12);
		assertThat(userRepository.findById(user.getId()).orElseThrow().getCreditBalance()).isEqualTo(12);
	}
}
