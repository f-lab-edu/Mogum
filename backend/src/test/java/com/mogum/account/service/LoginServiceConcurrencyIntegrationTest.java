package com.mogum.account.service;

import com.mogum.account.domain.User;
import com.mogum.account.domain.UserFixtures;
import com.mogum.account.domain.UserRole;
import com.mogum.account.domain.UserStatus;
import com.mogum.account.repository.UserRepository;
import com.mogum.config.AuthProperties;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class LoginServiceConcurrencyIntegrationTest {
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestClock clock;

    @ParameterizedTest(name = "기존 회원 상태 {0}")
    @EnumSource(UserStatus.class)
    @DisplayName("재로그인은 이메일 역할 상태 약관 가입시각 외부 식별자를 커밋 후에도 보존한다")
    void preservesCommittedUserRowOnRelogin(UserStatus status) {
        // given
        var user = existingUser(status);
        var before = row(user.getId());
        var login = service(users, List.of("new@example.com"));
        clock.advance(Duration.ofHours(1));

        // when
        var result = login.login(new ExternalAccount("google", "stable-sub", "new@example.com", true));

        // then
        assertThat(result).isEqualTo(new LoginResult.Success(user.getId()));
        assertThat(row(user.getId())).isEqualTo(before);
        assertThat(userCount()).isEqualTo(1);
    }

    @ParameterizedTest(name = "기존 회원 상태 {0}")
    @EnumSource(UserStatus.class)
    @DisplayName("허용 목록에서 제외된 기존 이메일로 거절되어도 회원 행을 보존한다")
    void preservesCommittedUserRowOnRejectedRelogin(UserStatus status) {
        // given
        var user = existingUser(status);
        var before = row(user.getId());
        var login = service(users, List.of("new@example.com"));
        clock.advance(Duration.ofHours(1));

        // when
        var result = login.login(new ExternalAccount("google", "stable-sub", "old@example.com", true));

        // then
        assertThat(result).isEqualTo(new LoginResult.Rejected(LoginResult.RejectionReason.NOT_ALLOWED));
        assertThat(row(user.getId())).isEqualTo(before);
        assertThat(userCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("동시 첫 로그인은 두 조회가 함께 지나도 unique 제약으로 회원 하나만 남긴다")
    void keepsOneUserAfterConcurrentFirstLogins() throws Exception {
        // given
        var account = new ExternalAccount("google", "concurrent-sub", "admin@example.com", true);

        // when
        var outcomes = concurrentFirstLogins(account);

        // then
        assertThat(outcomes.successes()).hasSize(1);
        assertThat(outcomes.failures()).singleElement().isInstanceOf(DataIntegrityViolationException.class);
        assertThat(userCount()).isEqualTo(1);
        var saved = users.findByProviderAndSubject("google", "concurrent-sub").orElseThrow();
        assertThat(outcomes.successes()).containsExactly(new LoginResult.Success(saved.getId()));
    }

    @Test
    @DisplayName("동시 첫 로그인 충돌 뒤 다음 로그인은 남아 있는 회원 ID를 반환한다")
    void returnsSurvivingUserAfterConcurrentConflict() throws Exception {
        // given
        var account = new ExternalAccount("google", "concurrent-sub", "admin@example.com", true);
        var outcomes = concurrentFirstLogins(account);
        assertThat(outcomes.successes()).hasSize(1);
        assertThat(outcomes.failures()).singleElement().isInstanceOf(DataIntegrityViolationException.class);
        var login = service(users, List.of("admin@example.com"));

        // when
        var result = login.login(account);

        // then
        assertThat(result).isEqualTo(outcomes.successes().getFirst());
        assertThat(userCount()).isEqualTo(1);
    }

    private User existingUser(UserStatus status) {
        var user = users.save(UserFixtures.user("google", "stable-sub", "old@example.com", UserRole.USER));
        jdbc.update("""
                UPDATE users SET status = ?, terms_agreed_at = ?, terms_version = 'v1', updated_at = ?
                WHERE id = ?
                """, status.name(), Timestamp.from(clock.instant().minusSeconds(100)),
                Timestamp.from(clock.instant()), user.getId());
        return user;
    }

    private ConcurrentLogins concurrentFirstLogins(ExternalAccount account) throws Exception {
        var barrier = new CyclicBarrier(2);
        // 조회만 가로채며 저장과 트랜잭션은 실제 레포지토리에 맡긴다. Spring 컨텍스트는 바꾸지 않는다.
        var synchronizedLookup = new UserRepository() {
            @Override
            public <S extends User> S save(S entity) {
                return users.save(entity);
            }

            @Override
            public Optional<User> findById(Long id) {
                return users.findById(id);
            }

            @Override
            public Optional<User> findByProviderAndSubject(String provider, String subject) {
                var result = users.findByProviderAndSubject(provider, subject);
                assertThat(result).isEmpty();
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Concurrent lookup interrupted", exception);
                } catch (java.util.concurrent.BrokenBarrierException | java.util.concurrent.TimeoutException exception) {
                    throw new IllegalStateException("Concurrent lookup failed", exception);
                }
                return result;
            }
        };
        var successes = new ArrayList<LoginResult.Success>();
        var failures = new ArrayList<Throwable>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var login = service(synchronizedLookup, List.of(account.email()));
            var futures = List.of(executor.submit(() -> login.login(account)), executor.submit(() -> login.login(account)));
            for (var future : futures) {
                try {
                    var result = future.get(15, TimeUnit.SECONDS);
                    assertThat(result).isInstanceOf(LoginResult.Success.class);
                    successes.add((LoginResult.Success) result);
                } catch (ExecutionException exception) {
                    failures.add(exception.getCause());
                }
            }
        }
        return new ConcurrentLogins(successes, failures);
    }

    private record ConcurrentLogins(List<LoginResult.Success> successes, List<Throwable> failures) { }

    private int userCount() {
        return jdbc.queryForObject("SELECT count(*) FROM users", Integer.class);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT * FROM users WHERE id = ?", id);
    }

    private static LoginService service(UserRepository repository, List<String> adminEmails) {
        return new LoginService(repository, new AuthProperties(adminEmails));
    }
}
