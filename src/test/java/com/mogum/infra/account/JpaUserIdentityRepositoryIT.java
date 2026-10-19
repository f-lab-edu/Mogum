package com.mogum.infra.account;

import com.mogum.core.TransactionRunner;
import com.mogum.core.account.*;
import com.mogum.support.PostgresTestConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class JpaUserIdentityRepositoryIT extends PostgresTestConfiguration {
    @Autowired private UserRepository users;
    @Autowired private UserIdentityRepository identities;
    @Autowired private TransactionRunner transactions;
    @Autowired private JdbcTemplate jdbc;
    private final ExternalIdentity identity = new ExternalIdentity("google", "Sub-" + UUID.randomUUID());
    private final Instant now = Instant.parse("2026-10-12T03:00:00Z");
    private long userId;

    @BeforeEach
    void createUser() {
        userId = transactions.inTransaction(() -> users.create("identity@example.com", UserStatus.ACTIVE, UserRole.USER, now)).id();
    }

    @AfterEach
    void clean() { jdbc.update("DELETE FROM users WHERE id = ?", userId); }

    @Test
    void 외부_식별자로_완전히_채운_record를_조회한다() {
        assertTrue(transactions.inTransaction(() -> identities.findByIdentity(identity)).isEmpty());
        var link = create(identity);
        assertTrue(link.id() > 0);
        assertEquals(new UserIdentity(link.id(), userId, identity, now), link);
        assertEquals(link, transactions.inTransaction(() -> identities.findByIdentity(identity).orElseThrow()));
    }

    @Test
    void 같은_provider_subject는_중복할_수_없다() {
        var link = create(identity);
        assertThrows(DataIntegrityViolationException.class, () -> create(identity));
        assertEquals(link, transactions.inTransaction(() -> identities.findByIdentity(identity).orElseThrow()));
    }

    @Test
    void 제공자가_다르거나_subject의_대소문자가_다르면_별도_연결이다() {
        var first = create(identity);
        var otherProvider = create(new ExternalIdentity("another-provider", identity.subject()));
        var otherSubject = create(new ExternalIdentity("google", identity.subject().toLowerCase(java.util.Locale.ROOT)));
        assertNotEquals(first.id(), otherProvider.id());
        assertNotEquals(first.id(), otherSubject.id());
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM user_identities WHERE user_id = ?", Integer.class, userId));
    }

    @Test
    void 사용자_삭제는_연결도_삭제한다() {
        create(identity);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
        assertTrue(transactions.inTransaction(() -> identities.findByIdentity(identity)).isEmpty());
        assertThrows(DataIntegrityViolationException.class, () -> create(identity));
    }

    @ParameterizedTest
    @ValueSource(strings = {"user_id", "provider", "subject", "created_at"})
    void 필수_컬럼은_NULL을_허용하지_않는다(String column) {
        var link = create(identity);
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE user_identities SET " + column + " = NULL WHERE id = ?", link.id()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t", "\n"})
    void 제공자와_subject는_빈_값을_허용하지_않는다(String blank) {
        assertThrows(DataIntegrityViolationException.class, () -> create(new ExternalIdentity(blank, identity.subject())));
        assertThrows(DataIntegrityViolationException.class, () -> create(new ExternalIdentity("google", blank)));
    }

    private UserIdentity create(ExternalIdentity key) {
        return transactions.inTransaction(() -> identities.create(userId, key, now));
    }
}
