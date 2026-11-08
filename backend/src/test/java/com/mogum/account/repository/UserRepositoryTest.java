package com.mogum.account.repository;

import com.mogum.account.domain.User;
import com.mogum.account.domain.UserFixtures;
import com.mogum.account.domain.UserRole;
import com.mogum.account.domain.UserStatus;
import com.mogum.support.RepositoryTest;
import com.mogum.support.TestClock;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@RepositoryTest
@ExtendWith(OutputCaptureExtension.class)
class UserRepositoryTest {
    @Autowired private UserRepository repository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestClock clock;
    @PersistenceContext private EntityManager entityManager;

    @Test
    @DisplayName("저장한 회원을 ID로 다시 읽으면 식별자와 가입 정보 및 시각이 보존된다")
    void savesAndReloadsUserById() {
        // given
        var saved = repository.save(UserFixtures.user("google", "Subject", "repository@example.com", UserRole.ADMIN));

        // when
        var actual = repository.findById(saved.getId()).orElseThrow();

        // then
        assertThat(saved.getId()).isPositive();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(actual.getId()).isEqualTo(saved.getId());
        assertThat(actual.getProvider()).isEqualTo("google");
        assertThat(actual.getSubject()).isEqualTo("Subject");
        assertThat(actual.getEmail()).isEqualTo("repository@example.com");
        assertThat(actual.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(actual.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(actual.getCreatedAt()).isEqualTo(clock.instant());
        assertThat(actual.getTermsAgreedAt()).isNull();
        assertThat(actual.getTermsVersion()).isNull();
    }

    @Test
    @DisplayName("없는 회원 ID는 빈 결과를 반환한다")
    void returnsEmptyForMissingId() {
        // when
        var actual = repository.findById(-1L);

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    @DisplayName("호출자 트랜잭션이 롤백되면 발급한 ID의 회원도 저장되지 않는다")
    void rollsBackUserWithCallerTransaction() {
        // given
        var failure = new IllegalStateException("rollback user creation");
        var saved = new AtomicReference<User>();
        var transactions = new TransactionTemplate(transactionManager);

        // when & then
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            saved.set(repository.save(UserFixtures.user("rollback@example.com")));
            throw failure;
        })).isSameAs(failure);
        assertThat(saved.get().getId()).isPositive();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(repository.findById(saved.get().getId())).isEmpty();
    }

    @Test
    @DisplayName("이메일이 같아도 외부 계정이 다르면 별도 회원을 저장한다")
    void allowsDuplicateEmailForDifferentIdentities() {
        // given
        var first = repository.save(UserFixtures.user("google", "first", "shared@example.com", UserRole.USER));

        // when
        var second = repository.save(UserFixtures.user("google", "second", "shared@example.com", UserRole.USER));

        // then
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(repository.findById(first.getId()).orElseThrow().getEmail()).isEqualTo("shared@example.com");
        assertThat(repository.findById(second.getId()).orElseThrow().getEmail()).isEqualTo("shared@example.com");
    }

    @Test
    @DisplayName("중복키 오류의 예외와 로그에는 제약 이름만 남고 외부 식별자 원문은 남지 않는다")
    void omitsSubjectFromDuplicateKeyExceptionAndLogs(CapturedOutput output) {
        // given
        String marker = "LEAKCHECK-private-subject";
        repository.save(UserFixtures.user("google", marker, "repository@example.com", UserRole.ADMIN));

        // when & then
        assertThatThrownBy(() -> repository.save(
                UserFixtures.user("google", marker, "repository@example.com", UserRole.ADMIN)))
                .satisfies(failure -> {
                    assertSqlState("23505", failure);
                    StringBuilder messages = new StringBuilder();
                    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                        messages.append(cause.getMessage()).append('\n');
                    }
                    assertThat(messages.toString()).contains("users_provider_subject_key").doesNotContain(marker);
                });
        assertThat(output.getAll()).contains("users_provider_subject_key").doesNotContain(marker);
    }

    @Test
    @DisplayName("외부 식별자의 공백과 대소문자를 보정하지 않고 원문으로 조회한다")
    void findsIdentityWithoutNormalizingSubject() {
        // given
        var saved = repository.save(UserFixtures.user("google", " Sub-Case ", "repository@example.com", UserRole.ADMIN));

        // when
        var actual = repository.findByProviderAndSubject("google", " Sub-Case ");

        // then
        assertThat(actual).get().extracting(User::getId).isEqualTo(saved.getId());
        assertThat(actual.orElseThrow().getSubject()).isEqualTo(" Sub-Case ");
    }

    @ParameterizedTest(name = "변형 식별자 [{0}]")
    @ValueSource(strings = {"Sub-Case", " sub-case "})
    @DisplayName("공백을 제거하거나 소문자로 바꾼 식별자는 기존 회원과 일치하지 않는다")
    void doesNotFindNormalizedSubject(String subject) {
        // given
        repository.save(UserFixtures.user("google", " Sub-Case ", "repository@example.com", UserRole.ADMIN));

        // when
        var actual = repository.findByProviderAndSubject("google", subject);

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    @DisplayName("저장하지 않은 외부 계정은 빈 결과를 반환한다")
    void returnsEmptyForMissingIdentity() {
        // when
        var actual = repository.findByProviderAndSubject("google", "missing");

        // then
        assertThat(actual).isEmpty();
    }

    @ParameterizedTest(name = "제공자 [{0}] 식별자 [{1}]")
    @CsvSource(value = {"google|''", "google|'  '", "google|'\t\n'", "''|Subject", "'  '|Subject", "'\t\n'|Subject"}, delimiter = '|')
    @DisplayName("빈 값이나 공백뿐인 제공자와 외부 식별자는 거절한다")
    void rejectsBlankIdentityParts(String provider, String subject) {
        // given
        var existing = repository.save(UserFixtures.user("google", "Subject", "repository@example.com", UserRole.ADMIN));

        // when & then
        assertThatThrownBy(() -> repository.save(
                UserFixtures.user(provider, subject, "repository@example.com", UserRole.ADMIN)))
                .satisfies(failure -> assertSqlState("23514", failure));
        assertThat(repository.findByProviderAndSubject("google", "Subject")).get()
                .extracting(User::getId).isEqualTo(existing.getId());
    }

    @Test
    @DisplayName("다른 제공자의 같은 식별자는 서로 다른 회원이며 각각 조회된다")
    void distinguishesProvidersWithSameSubject() {
        // given
        var first = repository.save(UserFixtures.user("google", "shared", "repository@example.com", UserRole.USER));
        assertThat(repository.findByProviderAndSubject("other-provider", "shared")).isEmpty();

        // when
        var second = repository.save(UserFixtures.user("other-provider", "shared", "repository@example.com", UserRole.USER));

        // then
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(repository.findByProviderAndSubject("google", "shared")).get()
                .extracting(User::getId).isEqualTo(first.getId());
        assertThat(repository.findByProviderAndSubject("other-provider", "shared")).get()
                .extracting(User::getId).isEqualTo(second.getId());
    }

    @ParameterizedTest(name = "컬럼 {0}")
    @ValueSource(strings = {"status", "role"})
    @DisplayName("허용하지 않은 상태와 역할 갱신은 거절하고 기존 회원을 보존한다")
    void rejectsUnknownStatusAndRole(String column) {
        // given
        var saved = repository.save(UserFixtures.user("google", "Subject", "repository@example.com", UserRole.ADMIN));

        // when & then
        assertThatThrownBy(() -> jdbc.update("UPDATE users SET " + column
                + " = 'INVALID', updated_at = ? WHERE id = ?", Timestamp.from(clock.instant()), saved.getId()))
                .satisfies(failure -> assertSqlState("23514", failure));
        assertUnchanged(saved);
    }

    @ParameterizedTest(name = "필수 컬럼 {0}")
    @ValueSource(strings = {"provider", "subject", "email", "status", "role", "created_at", "updated_at"})
    @DisplayName("회원 필수 컬럼은 NULL을 거절하고 기존 회원을 보존한다")
    void rejectsNullRequiredColumn(String column) {
        // given
        var saved = repository.save(UserFixtures.user("google", "Subject", "repository@example.com", UserRole.ADMIN));
        String audit = column.equals("updated_at") ? "" : ", updated_at = ?";
        Object[] parameters = column.equals("updated_at") ? new Object[]{saved.getId()}
                : new Object[]{Timestamp.from(clock.instant()), saved.getId()};

        // when & then
        assertThatThrownBy(() -> jdbc.update("UPDATE users SET " + column
                + " = NULL" + audit + " WHERE id = ?", parameters))
                .satisfies(failure -> assertSqlState("23502", failure));
        assertUnchanged(saved);
    }

    @Test
    @DisplayName("명시적으로 flush한 JPA 변경은 같은 트랜잭션의 네이티브 조회에 보인다")
    void exposesFlushedChangesToNativeQuery() {
        // given
        var saved = repository.save(UserFixtures.user("repository@example.com"));
        var transactions = new TransactionTemplate(transactionManager);

        // when
        var emails = transactions.execute(status -> {
            UserFixtures.withEmail(entityManager.find(User.class, saved.getId()), "flushed@example.com");
            entityManager.flush();
            return List.of(nativeEmail(saved.getId()), repository.findById(saved.getId()).orElseThrow().getEmail());
        });

        // then
        assertThat(emails).containsExactly("flushed@example.com", "flushed@example.com");
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test
    @DisplayName("명시적 flush 없는 네이티브 조회에서도 현재 Hibernate 설정은 변경값을 관찰한다")
    void observesNativeReadWithoutExplicitFlush() {
        // given
        var saved = repository.save(UserFixtures.user("repository@example.com"));
        var transactions = new TransactionTemplate(transactionManager);

        // when
        var email = transactions.execute(status -> {
            UserFixtures.withEmail(entityManager.find(User.class, saved.getId()), "auto-flush@example.com");
            // 현재 설정의 관찰이다. 제품 코드는 이 자동 flush에 기대지 않는다.
            return nativeEmail(saved.getId());
        });

        // then
        assertThat(email).isEqualTo("auto-flush@example.com");
    }

    private void assertUnchanged(User saved) {
        var actual = repository.findById(saved.getId()).orElseThrow();
        assertThat(actual.getId()).isEqualTo(saved.getId());
        assertThat(actual.getEmail()).isEqualTo(saved.getEmail());
        assertThat(actual.getStatus()).isEqualTo(saved.getStatus());
        assertThat(actual.getRole()).isEqualTo(saved.getRole());
        assertThat(actual.getCreatedAt()).isEqualTo(saved.getCreatedAt());
    }

    private String nativeEmail(long id) {
        return (String) entityManager.createNativeQuery("SELECT email FROM users WHERE id = :id")
                .setParameter("id", id).getSingleResult();
    }

    private static void assertSqlState(String expected, Throwable failure) {
        assertThat(failure).isNotNull();
        assertThat(failure).hasRootCauseInstanceOf(SQLException.class);
        Throwable root = failure;
        while (root.getCause() != null) root = root.getCause();
        assertThat(root).isInstanceOfSatisfying(SQLException.class,
                sql -> assertThat(sql.getSQLState()).isEqualTo(expected));
    }
}
