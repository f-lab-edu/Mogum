package com.mogum.account.service;

import com.mogum.account.domain.ExternalIdentity;
import com.mogum.account.domain.User;
import com.mogum.account.domain.UserRole;
import com.mogum.account.domain.UserStatus;
import com.mogum.account.repository.UserRepository;
import com.mogum.config.AuthProperties;
import com.mogum.support.PostgresTestConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

@SpringBootTest
class LoginServiceIT extends PostgresTestConfiguration {
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    private final String email = "login-" + UUID.randomUUID() + "@example.com";
    private final ExternalIdentity identity = new ExternalIdentity("google", "Sub-" + UUID.randomUUID());
    private final Instant now = Instant.parse("2026-10-12T03:00:00Z");

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM users WHERE email = ?", email);
    }

    @Test
    void 미인증과_목록_밖과_빈_목록은_회원을_만들지_않는다() {
        assertEquals(new LoginResult.Rejected(LoginResult.RejectionReason.EMAIL_NOT_VERIFIED),
                login(users).login(new ExternalAccount(identity, email, false)));
        assertEquals(new LoginResult.Rejected(LoginResult.RejectionReason.NOT_ALLOWED),
                login(users).login(new ExternalAccount(identity, "outside@example.com", true)));
        assertEquals(new LoginResult.Rejected(LoginResult.RejectionReason.NOT_ALLOWED),
                service(users, List.of()).login(account()));
        assertEquals(0, userCount());
        assertTrue(find(identity).isEmpty());
    }

    @Test
    void 첫_로그인은_외부_식별자를_회원에_저장하고_재로그인은_동일_ID를_반환한다() {
        var result = assertInstanceOf(LoginResult.Success.class, login(users).login(account()));
        var user = users.findById(result.userId()).orElseThrow();
        assertEquals(email, user.getEmail());
        assertEquals(UserStatus.ACTIVE, user.getStatus());
        assertEquals(UserRole.ADMIN, user.getRole());
        assertNull(user.getTermsAgreedAt());
        assertNull(user.getTermsVersion());
        assertEquals(now, user.getCreatedAt());
        assertEquals(user.getId(), find(identity).orElseThrow().getId());
        assertEquals(identity.subject(), jdbc.queryForObject("SELECT subject FROM users WHERE id = ?", String.class, user.getId()));
        assertEquals(identity.provider(), jdbc.queryForObject("SELECT provider FROM users WHERE id = ?", String.class, user.getId()));
        assertEquals(result, login(users).login(account()));
        assertEquals(1, userCount());
    }

    @ParameterizedTest
    @EnumSource(UserStatus.class)
    void 재로그인은_이메일_역할_상태_약관_가입시각과_외부_식별자를_보존한다(UserStatus status) {
        var user = users.save(new User(identity, email, status, UserRole.USER, now.minusSeconds(200)));
        jdbc.update("UPDATE users SET terms_agreed_at = ?, terms_version = 'v1' WHERE id = ?",
                java.sql.Timestamp.from(now.minusSeconds(100)), user.getId());
        var before = row(user.getId());
        var login = service(users, List.of("new@example.com"));
        assertEquals(new LoginResult.Success(user.getId()), login.login(new ExternalAccount(identity, "new@example.com", true)));
        assertEquals(before, row(user.getId()));
        assertEquals(new LoginResult.Rejected(LoginResult.RejectionReason.NOT_ALLOWED), login.login(account()));
        assertEquals(before, row(user.getId()));
        assertEquals(1, userCount());
    }

    @Test
    void 이메일이_같아도_다른_제공자의_같은_subject은_자동_병합하지_않는다() {
        var first = login(users).login(account());
        var second = login(users).login(new ExternalAccount(new ExternalIdentity("other-provider", identity.subject()), email, true));
        assertNotEquals(first, second);
        assertEquals(2, userCount());
    }

    @Test
    void 동시_첫_로그인은_외부_식별자_unique로_회원_하나만_남긴다() throws Exception {
        var barrier = new CyclicBarrier(2);
        var synchronizedLookup = mock(UserRepository.class, delegatesTo(users));
        doAnswer(invocation -> {
            var result = users.findByProviderAndSubject(invocation.getArgument(0), invocation.getArgument(1));
            assertTrue(result.isEmpty());
            barrier.await(10, TimeUnit.SECONDS);
            return result;
        }).when(synchronizedLookup).findByProviderAndSubject(anyString(), anyString());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var login = login(synchronizedLookup);
            var futures = List.of(executor.submit(() -> login.login(account())), executor.submit(() -> login.login(account())));
            int successes = 0;
            int conflicts = 0;
            for (var future : futures) {
                try {
                    assertInstanceOf(LoginResult.Success.class, future.get(15, TimeUnit.SECONDS));
                    successes++;
                } catch (ExecutionException failure) {
                    assertInstanceOf(org.springframework.dao.DataIntegrityViolationException.class, failure.getCause());
                    conflicts++;
                }
            }
            assertEquals(1, successes);
            assertEquals(1, conflicts);
        }
        assertEquals(1, userCount());
        var user = find(identity).orElseThrow();
        assertEquals(new LoginResult.Success(user.getId()), login(users).login(account()));
    }

    private int userCount() { return jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email); }
    private Map<String, Object> row(long id) { return jdbc.queryForMap("SELECT * FROM users WHERE id = ?", id); }
    private Optional<User> find(ExternalIdentity key) { return users.findByProviderAndSubject(key.provider(), key.subject()); }
    private ExternalAccount account() { return new ExternalAccount(identity, email, true); }
    private LoginService login(UserRepository repository) { return service(repository, List.of(email)); }
    private LoginService service(UserRepository repository, List<String> adminEmails) {
        return new LoginService(repository, Clock.fixed(now, ZoneOffset.UTC), new AuthProperties(adminEmails));
    }
}
