package com.mogum.core.account;

import com.mogum.core.TransactionRunner;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class LoginIT extends PostgresTestConfiguration {
    @Autowired private UserRepository users;
    @Autowired private UserIdentityRepository identities;
    @Autowired private TransactionRunner transactions;
    @Autowired private JdbcTemplate jdbc;
    private final String email = "login-" + UUID.randomUUID() + "@example.com";
    private final ExternalIdentity identity = new ExternalIdentity("google", UUID.randomUUID().toString());
    private final Instant now = Instant.parse("2026-10-12T03:00:00Z");

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM users WHERE email = ?", email);
    }

    @Test
    void 미인증과_목록_밖과_빈_목록은_회원과_연결을_만들지_않는다() {
        assertEquals(new LoginResult.Rejected(LoginResult.RejectionReason.EMAIL_NOT_VERIFIED),
                login(identities).login(new ExternalAccount(identity, email, false)));
        assertEquals(new LoginResult.Rejected(LoginResult.RejectionReason.NOT_ALLOWED),
                login(identities).login(new ExternalAccount(identity, "outside@example.com", true)));
        assertEquals(new LoginResult.Rejected(LoginResult.RejectionReason.NOT_ALLOWED),
                new Login(users, identities, transactions, Clock.fixed(now, ZoneOffset.UTC), List.of()).login(account()));
        assertEquals(0, userCount());
        assertTrue(transactions.inTransaction(() -> identities.findByIdentity(identity)).isEmpty());
    }

    @Test
    void 첫_로그인은_회원과_연결을_생성하고_재로그인은_동일_ID를_반환한다() {
        var result = assertInstanceOf(LoginResult.Success.class, login(identities).login(account()));
        var user = transactions.inTransaction(() -> users.findById(result.userId()).orElseThrow());
        assertEquals(new User(result.userId(), email, UserStatus.ACTIVE, UserRole.ADMIN, null, null, now), user);
        var link = transactions.inTransaction(() -> identities.findByIdentity(identity).orElseThrow());
        assertEquals(user.id(), link.userId());
        assertEquals(now, link.createdAt());
        assertEquals(result, login(identities).login(account()));
        assertEquals(1, userCount());
        assertEquals(link, transactions.inTransaction(() -> identities.findByIdentity(identity).orElseThrow()));
    }

    @ParameterizedTest
    @EnumSource(UserStatus.class)
    void 재로그인은_이메일_역할_상태_약관_가입시각과_연결을_보존한다(UserStatus status) {
        var user = transactions.inTransaction(() -> {
            var created = users.create(email, status, UserRole.USER, now.minusSeconds(200));
            identities.create(created.id(), identity, now.minusSeconds(150));
            return created;
        });
        jdbc.update("UPDATE users SET terms_agreed_at = ?, terms_version = 'v1' WHERE id = ?",
                java.sql.Timestamp.from(now.minusSeconds(100)), user.id());
        var snapshot = transactions.inTransaction(() -> users.findById(user.id()).orElseThrow());
        var link = transactions.inTransaction(() -> identities.findByIdentity(identity).orElseThrow());
        var login = new Login(users, identities, transactions, Clock.fixed(now, ZoneOffset.UTC), List.of("new@example.com"));
        assertEquals(new LoginResult.Success(user.id()), login.login(new ExternalAccount(identity, "new@example.com", true)));
        assertEquals(snapshot, transactions.inTransaction(() -> users.findById(user.id()).orElseThrow()));
        assertEquals(link, transactions.inTransaction(() -> identities.findByIdentity(identity).orElseThrow()));
        assertEquals(new LoginResult.Rejected(LoginResult.RejectionReason.NOT_ALLOWED), login.login(account()));
        assertEquals(snapshot, transactions.inTransaction(() -> users.findById(user.id()).orElseThrow()));
    }

    @Test
    void 이메일이_같아도_다른_외부_계정은_자동_연결하지_않는다() {
        var first = login(identities).login(account());
        var second = login(identities).login(new ExternalAccount(new ExternalIdentity("other-provider", identity.subject()), email, true));
        assertNotEquals(first, second);
        assertEquals(2, userCount());
    }

    @Test
    void 연결_저장이_실패하면_먼저_생성한_회원도_롤백된다() {
        var failure = new IllegalStateException("identity insert failed");
        var failing = new UserIdentityRepository() {
            public Optional<UserIdentity> findByIdentity(ExternalIdentity key) { return Optional.empty(); }
            public UserIdentity create(long userId, ExternalIdentity key, Instant createdAt) {
                assertTrue(users.findById(userId).isPresent());
                throw failure;
            }
        };
        assertSame(failure, assertThrows(IllegalStateException.class, () -> login(failing).login(account())));
        assertEquals(0, userCount());
        assertTrue(transactions.inTransaction(() -> identities.findByIdentity(identity)).isEmpty());
    }

    @Test
    void 동시_첫_로그인의_패자는_연결_없는_회원을_남기지_않는다() throws Exception {
        var barrier = new CyclicBarrier(2);
        var synchronizedLookup = new UserIdentityRepository() {
            public Optional<UserIdentity> findByIdentity(ExternalIdentity key) {
                var result = identities.findByIdentity(key);
                assertTrue(result.isEmpty());
                try { barrier.await(10, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
                return result;
            }
            public UserIdentity create(long userId, ExternalIdentity key, Instant createdAt) {
                return identities.create(userId, key, createdAt);
            }
        };
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
        var userId = transactions.inTransaction(() -> identities.findByIdentity(identity).orElseThrow().userId());
        assertEquals(new LoginResult.Success(userId), login(identities).login(account()));
    }

    private int userCount() { return jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email); }
    private ExternalAccount account() { return new ExternalAccount(identity, email, true); }
    private Login login(UserIdentityRepository links) {
        return new Login(users, links, transactions, Clock.fixed(now, ZoneOffset.UTC), List.of(email));
    }
}
