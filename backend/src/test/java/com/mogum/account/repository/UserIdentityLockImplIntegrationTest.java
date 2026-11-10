package com.mogum.account.repository;

import com.mogum.auth.AccountPrincipal;
import com.mogum.config.CreditProperties;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestRequests;
import com.mogum.support.TestSessions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.web.servlet.client.RestTestClient;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class UserIdentityLockImplIntegrationTest {
    @Autowired TestRequests requests;
    @Autowired JdbcIndexedSessionRepository sessions;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired CreditProperties credit;
    @Autowired WebServerApplicationContext context;

    @ParameterizedTest(name = "서로 다른 세션 {0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("같은 신원의 동시 신청은 실제 HTTP와 DB에서 함께 잠금을 기다리고 세션 수에 관계없이 한 번만 채운다")
    void serializesConcurrentApplicationsAcrossSessions(boolean separateSessions) throws Exception {
        // given
        var identity = new AccountPrincipal.NotApplied("google", "concurrent-sub", "concurrent@example.com", "동시 신청");
        var first = identityClient(identity);
        var second = separateSessions ? identityClient(identity) : first;
        var server = (TomcatWebServer) context.getWebServer();
        var protocol = (org.apache.coyote.AbstractProtocol<?>) server.getTomcat().getConnector().getProtocolHandler();
        int originalMax = protocol.getMaxThreads();
        // 이 검사 동안만 HTTP 두 요청을 동시에 처리한다. 공용 컨텍스트와 MDC 검사의 단일 스레드를 복구한다.
        protocol.setMaxThreads(2);
        try (var connection = dataSource.getConnection(); var executor = Executors.newFixedThreadPool(2)) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                statement.setString(1, "account.apply:" + identity.provider() + ":" + identity.subject());
                statement.execute();
            }

            // when
            var futures = List.of(
                    executor.submit(() -> first.post().uri("/api/account/apply").exchange().returnResult(String.class)),
                    executor.submit(() -> second.post().uri("/api/account/apply").exchange().returnResult(String.class)));
            long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
            int waiting;
            do {
                waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event = 'advisory'", Integer.class);
                if (waiting < 2) Thread.sleep(10);
            } while (waiting < 2 && System.nanoTime() < deadline);
            // 두 트랜잭션이 실제로 함께 DB에서 기다렸는지 확인한다.
            connection.commit();

            // then
            assertThat(waiting).isEqualTo(2);
            for (var future : futures) {
                var response = future.get(15, TimeUnit.SECONDS);
                // 같은 세션은 ID 회전 때문에 응답 계약이 없다. 행과 기록만 검증한다.
                if (separateSessions) {
                    assertThat(response.getStatus().value()).isEqualTo(200);
                    assertThat(response.getResponseBody()).contains("\"status\":\"APPLIED\"");
                    assertThat(response.getResponseCookies().getFirst("SESSION")).isNotNull();
                }
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_entries", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT credit_balance FROM users", Integer.class)).isEqualTo(credit.monthlyAmount());
            assertThat(jdbc.queryForObject("SELECT u.credit_balance = sum(e.amount) FROM users u JOIN credit_entries e ON e.user_id = u.id GROUP BY u.id", Boolean.class)).isTrue();
        } finally {
            protocol.setMaxThreads(originalMax);
            var executor = (org.apache.tomcat.util.threads.ThreadPoolExecutor) protocol.getExecutor();
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (executor.getPoolSize() > originalMax && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(executor.getPoolSize()).isLessThanOrEqualTo(originalMax);
        }
    }

    private RestTestClient identityClient(AccountPrincipal.NotApplied identity) {
        var cookie = new TestSessions(sessions).createNotApplied(identity);
        var client = requests.anonymous().mutate().defaultCookie("SESSION", cookie.getValue()).build();
        var token = client.get().uri("/api/me").exchange().returnResult().getResponseCookies().getFirst("XSRF-TOKEN");
        return client.mutate().defaultCookie("XSRF-TOKEN", token.getValue()).defaultHeader("X-XSRF-TOKEN", token.getValue()).build();
    }
}
