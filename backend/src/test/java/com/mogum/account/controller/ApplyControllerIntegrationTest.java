package com.mogum.account.controller;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.domain.UserStatus;
import com.mogum.account.repository.UserRepository;
import com.mogum.auth.AccountPrincipal;
import com.mogum.config.CreditProperties;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestRequests;
import com.mogum.support.TestSessions;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class ApplyControllerIntegrationTest {
    @Autowired TestRequests requests;
    @Autowired JdbcIndexedSessionRepository sessions;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired CreditProperties credit;

    private final AccountPrincipal.NotApplied identity = new AccountPrincipal.NotApplied(
            "google", "apply-sub", "LEAKCHECK-apply-email@example.com", "LEAKCHECK-apply-name");

    @Test
    @DisplayName("신청은 첫 몫을 넣고 세션을 회전하여 사용자 ID와 주인 색인만 남긴다")
    void appliesAndConvertsIdentitySession() {
        // given
        var helper = new TestSessions(sessions);
        var cookie = helper.createNotApplied(identity);
        var client = withCsrf(cookie);

        // when
        var response = client.post().uri("/api/account/apply").exchange().returnResult(String.class);

        // then
        assertApplied(response);
        var next = response.getResponseCookies().getFirst("SESSION");
        assertThat(next).isNotNull();
        var nextCookie = new Cookie("SESSION", next.getValue());
        assertThat(helper.id(nextCookie)).isNotEqualTo(helper.id(cookie));
        assertThat(sessions.findById(helper.id(cookie))).isNull();
        Session stored = sessions.findById(helper.id(nextCookie));
        var user = users.findByProviderAndSubject(identity.provider(), identity.subject()).orElseThrow();
        assertThat(stored.getAttributeNames()).containsExactly(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME);
        assertThat((String) stored.getAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME)).isEqualTo(user.getId().toString());
        assertThat(jdbc.queryForObject("SELECT principal_name FROM spring_session WHERE session_id = ?", String.class, stored.getId()))
                .isEqualTo(user.getId().toString());
        assertThat(sessions.findByPrincipalName(user.getId().toString())).containsKey(stored.getId());
        requests.anonymous().get().uri("/api/me").cookie("SESSION", next.getValue()).exchange()
                .expectStatus().isOk().expectBody().json(expectedApplied(), JsonCompareMode.STRICT);
        assertSingleRefill();
    }

    @Test
    @DisplayName("순서대로 두 번 신청하면 같은 결과를 돌려주고 행과 기록을 더하지 않는다")
    void appliesTwiceWithoutChangingAccount() {
        // given
        var cookie = new TestSessions(sessions).createNotApplied(identity);
        var first = withCsrf(cookie).post().uri("/api/account/apply").exchange().returnResult(String.class);
        assertApplied(first);
        var next = first.getResponseCookies().getFirst("SESSION");
        var client = withCsrf(new Cookie("SESSION", next.getValue()));
        var before = jdbc.queryForMap("SELECT * FROM users");
        var entries = jdbc.queryForList("SELECT * FROM credit_entries");

        // when
        var response = client.post().uri("/api/account/apply").exchange().returnResult(String.class);

        // then
        assertApplied(response);
        assertThat(jdbc.queryForMap("SELECT * FROM users")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM credit_entries")).isEqualTo(entries);
        assertThat(response.getResponseCookies().getFirst("SESSION")).isNull();
        assertSingleRefill();
    }

    @ParameterizedTest(name = "기존 상태 {0}")
    @EnumSource(UserStatus.class)
    @DisplayName("이미 신청한 회원은 현재 상태를 성공으로 반환하고 DB와 세션을 보존한다")
    void preservesEveryExistingMemberState(UserStatus status) {
        // given
        var user = users.save(UserFixtures.withStatus(UserFixtures.user("existing@example.com"), status));
        var cookie = new TestSessions(sessions).create(user.getId());
        var before = jdbc.queryForMap("SELECT * FROM users");
        var client = withCsrf(cookie);

        // when
        var response = client.post().uri("/api/account/apply").exchange();

        // then
        response.expectStatus().isOk().expectBody().json("""
                {"data":{"status":"%s","email":"existing@example.com","name":"existing"}}
                """.formatted(status), JsonCompareMode.STRICT);
        assertThat(jdbc.queryForMap("SELECT * FROM users")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_entries", Integer.class)).isZero();
        assertThat(sessions.findById(new TestSessions(sessions).id(cookie))).isNotNull();
    }

    @Test
    @DisplayName("CSRF 없는 신청은 신원과 행을 바꾸지 않고 CSRF 오류로 막는다")
    void rejectsApplyWithoutCsrf() {
        // given
        var cookie = new TestSessions(sessions).createNotApplied(identity);

        // when
        var response = requests.anonymous().post().uri("/api/account/apply").cookie("SESSION", cookie.getValue()).exchange();

        // then
        response.expectStatus().isForbidden().expectBody().json("""
                {"code":"CSRF_INVALID","message":"요청을 확인할 수 없습니다"}
                """, JsonCompareMode.STRICT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
        Session stored = sessions.findById(new TestSessions(sessions).id(cookie));
        assertThat((Object) stored.getAttribute(AccountPrincipal.NOT_APPLIED_SESSION_ATTRIBUTE)).isEqualTo(identity);
    }

    @Test
    @DisplayName("신청은 기존 접근 줄 외 INFO 이상 로그와 이메일 이름 원문을 남기지 않는다")
    void logsOnlyAccessWithoutIdentity(CapturedOutput output) throws Exception {
        // given
        var client = withCsrf(new TestSessions(sessions).createNotApplied(identity));
        int offset = output.getAll().length();

        // when
        var response = client.post().uri("/api/account/apply").exchange().returnResult(String.class);

        // then
        assertApplied(response);
        String id = response.getResponseHeaders().getFirst("X-Request-Id");
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!output.getAll().substring(offset).contains("path=/api/account/apply status=200") && System.nanoTime() < deadline) Thread.sleep(10);
        var logs = output.getAll().substring(offset);
        assertThat(logs).doesNotContain(identity.email(), identity.name(), identity.subject());
        assertThat(logs.lines().filter(line -> line.contains(" INFO ") || line.contains(" WARN ") || line.contains(" ERROR ")))
                .singleElement().satisfies(line -> assertThat(line).contains("requestId=" + id, "access method=POST path=/api/account/apply status=200"));
    }

    @Test
    @DisplayName("채움 기록 저장이 실패하면 회원과 잔액도 롤백하고 신원 세션을 유지한다")
    void rollsBackAccountWhenRefillCannotBeSaved(CapturedOutput output) throws Exception {
        // given
        var helper = new TestSessions(sessions);
        var cookie = helper.createNotApplied(identity);
        var client = withCsrf(cookie);
        jdbc.execute("ALTER TABLE credit_entries ADD CONSTRAINT apply_test_refill_failure CHECK (amount = 0)");
        try {
            // when
            var response = client.post().uri("/api/account/apply").exchange().returnResult(String.class);

            // then
            assertThat(response.getStatus().value()).isEqualTo(500);
            org.springframework.test.json.JsonAssert.comparator(JsonCompareMode.STRICT).assertIsMatch(
                    "{\"code\":\"INTERNAL_ERROR\",\"message\":\"요청을 처리할 수 없습니다\"}", response.getResponseBody());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_entries", Integer.class)).isZero();
            assertThat(response.getResponseCookies().getFirst("SESSION")).isNull();
            Session stored = sessions.findById(helper.id(cookie));
            assertThat(stored.getAttributeNames()).containsExactly(AccountPrincipal.NOT_APPLIED_SESSION_ATTRIBUTE);
            assertThat((Object) stored.getAttribute(AccountPrincipal.NOT_APPLIED_SESSION_ATTRIBUTE)).isEqualTo(identity);
            assertThat(output.getAll()).doesNotContain(identity.email(), identity.name(), identity.subject());
        } finally {
            jdbc.execute("ALTER TABLE credit_entries DROP CONSTRAINT apply_test_refill_failure");
        }
    }

    RestTestClient withCsrf(Cookie cookie) {
        var client = requests.anonymous().mutate().defaultCookie("SESSION", cookie.getValue()).build();
        var token = client.get().uri("/api/me").exchange().returnResult().getResponseCookies().getFirst("XSRF-TOKEN");
        return client.mutate().defaultCookie("XSRF-TOKEN", token.getValue()).defaultHeader("X-XSRF-TOKEN", token.getValue()).build();
    }

    private String expectedApplied() {
        return "{\"data\":{\"status\":\"APPLIED\",\"email\":\"" + identity.email() + "\",\"name\":\"" + identity.name() + "\"}}";
    }

    private void assertApplied(EntityExchangeResult<String> response) {
        assertThat(response.getStatus().value()).isEqualTo(200);
        org.springframework.test.json.JsonAssert.comparator(JsonCompareMode.STRICT).assertIsMatch(expectedApplied(), response.getResponseBody());
    }

    private void assertSingleRefill() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_entries", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT type, amount FROM credit_entries"))
                .containsEntry("type", "REFILL").containsEntry("amount", credit.monthlyAmount());
        assertThat(jdbc.queryForObject("SELECT credit_balance FROM users", Integer.class)).isEqualTo(credit.monthlyAmount());
        assertThat(jdbc.queryForObject("SELECT u.credit_balance = sum(e.amount) FROM users u JOIN credit_entries e ON e.user_id = u.id GROUP BY u.id", Boolean.class)).isTrue();
    }
}
