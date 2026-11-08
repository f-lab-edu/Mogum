package com.mogum.account.service;

import com.mogum.account.domain.User;
import com.mogum.account.domain.UserFixtures;
import com.mogum.account.domain.UserRole;
import com.mogum.account.domain.UserStatus;
import com.mogum.account.repository.FakeUserRepository;
import com.mogum.account.repository.UserRepository;
import com.mogum.config.AuthProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class LoginServiceTest {
    @Mock private UserRepository untouchedUsers;

    @ParameterizedTest(name = "거절 사유 {0}")
    @MethodSource("rejectedLogins")
    @DisplayName("미인증과 허용 목록 밖 및 빈 목록은 DB 접근 전에 거절한다")
    void rejectsDisallowedAccountBeforeDatabaseAccess(RejectedLogin scenario) {
        // given
        var login = service(untouchedUsers, scenario.adminEmails());

        // when
        var result = login.login(scenario.account());

        // then
        assertThat(result).isEqualTo(new LoginResult.Rejected(scenario.reason()));
        then(untouchedUsers).shouldHaveNoInteractions();
    }

    @ParameterizedTest(name = "거절 사유 {0}")
    @MethodSource("rejectedLogins")
    @DisplayName("거절된 로그인은 회원을 만들지 않는다")
    void doesNotCreateUserForRejectedLogin(RejectedLogin scenario) {
        // given
        var users = new FakeUserRepository();
        var login = service(users, scenario.adminEmails());

        // when
        var result = login.login(scenario.account());

        // then
        assertThat(result).isEqualTo(new LoginResult.Rejected(scenario.reason()));
        assertThat(users.count()).isZero();
        assertThat(users.findByProviderAndSubject("google", "sub")).isEmpty();
    }

    @Test
    @DisplayName("첫 로그인은 원본 이메일과 외부 식별자를 가진 ACTIVE ADMIN 회원을 만든다")
    void createsActiveAdminOnFirstLogin() {
        // given
        var users = new FakeUserRepository();
        var login = service(users, List.of("  ADMIN@example.com  ", "second@example.com"));
        var account = new ExternalAccount("google", "new-sub", "  Admin@EXAMPLE.COM  ", true);

        // when
        var result = login.login(account);

        // then
        var saved = users.findByProviderAndSubject("google", "new-sub").orElseThrow();
        assertThat(saved.getId()).isPositive();
        assertThat(result).isEqualTo(new LoginResult.Success(saved.getId()));
        assertThat(users.findById(saved.getId())).containsSame(saved);
        assertThat(users.count()).isEqualTo(1);
        assertThat(saved).extracting(User::getProvider, User::getSubject, User::getEmail,
                        User::getStatus, User::getRole, User::getTermsAgreedAt, User::getTermsVersion)
                .containsExactly("google", "new-sub", "  Admin@EXAMPLE.COM  ",
                        UserStatus.ACTIVE, UserRole.ADMIN, null, null);
    }

    @ParameterizedTest(name = "기존 회원 상태 {0}")
    @EnumSource(UserStatus.class)
    @DisplayName("재로그인은 상태와 관계없이 기존 ID를 반환하고 회원을 추가하지 않는다")
    void returnsExistingUserIdOnRelogin(UserStatus status) {
        // given
        var users = new FakeUserRepository();
        var existing = users.save(UserFixtures.withStatus(UserFixtures.withId(
                UserFixtures.user("google", "stable-sub", "old@example.com", UserRole.USER), 12L), status));
        var login = service(users, List.of("second@example.com"));

        // when
        var result = login.login(new ExternalAccount("google", "stable-sub", "second@example.com", true));

        // then
        assertThat(result).isEqualTo(new LoginResult.Success(existing.getId()));
        assertThat(users.count()).isEqualTo(1);
    }

    @ParameterizedTest(name = "제공자 [{0}] 식별자 [{1}]")
    @CsvSource({"other-provider, shared", "Google, shared", "google, Shared", "google, other-sub"})
    @DisplayName("같은 이메일이어도 제공자와 식별자가 정확히 일치하지 않으면 새 회원을 만든다")
    void createsSeparateUserForDifferentIdentity(String provider, String subject) {
        // given
        var users = new FakeUserRepository();
        var login = service(users, List.of("admin@example.com"));
        var first = login.login(new ExternalAccount("google", "shared", "admin@example.com", true));

        // when
        var second = login.login(new ExternalAccount(provider, subject, "admin@example.com", true));

        // then
        assertThat(second).isInstanceOf(LoginResult.Success.class).isNotEqualTo(first);
        assertThat(users.count()).isEqualTo(2);
        var saved = users.findByProviderAndSubject(provider, subject).orElseThrow();
        assertThat(second).isEqualTo(new LoginResult.Success(saved.getId()));
    }

    @ParameterizedTest(name = "이메일 [{0}] 역할 {1}")
    @CsvSource({"' ADMIN@Example.COM ', ADMIN", "second@example.com, ADMIN", "other@example.com, USER"})
    @DisplayName("역할은 정규화한 관리자 허용 목록으로 계산한다")
    void calculatesRoleFromAdminAllowlist(String email, UserRole expected) {
        // given
        var login = service(new FakeUserRepository(), List.of("  ADMIN@example.com  ", "second@example.com"));

        // when
        var role = login.roleFor(email);

        // then
        assertThat(role).isEqualTo(expected);
    }

    @Test
    @DisplayName("관리자 목록이 비어 있으면 일반 회원 역할을 계산한다")
    void calculatesUserRoleForEmptyAllowlist() {
        // given
        var login = service(new FakeUserRepository(), List.of());

        // when
        var role = login.roleFor("admin@example.com");

        // then
        assertThat(role).isEqualTo(UserRole.USER);
    }

    private static Stream<RejectedLogin> rejectedLogins() {
        return Stream.of(
                new RejectedLogin(List.of("admin@example.com"),
                        new ExternalAccount("google", "sub", "admin@example.com", false),
                        LoginResult.RejectionReason.EMAIL_NOT_VERIFIED),
                new RejectedLogin(List.of("admin@example.com"),
                        new ExternalAccount("google", "sub", "outside@example.com", true),
                        LoginResult.RejectionReason.NOT_ALLOWED),
                new RejectedLogin(List.of(),
                        new ExternalAccount("google", "sub", "admin@example.com", true),
                        LoginResult.RejectionReason.NOT_ALLOWED),
                new RejectedLogin(List.of("", "  "),
                        new ExternalAccount("google", "sub", "admin@example.com", true),
                        LoginResult.RejectionReason.NOT_ALLOWED));
    }

    private record RejectedLogin(List<String> adminEmails, ExternalAccount account,
                                 LoginResult.RejectionReason reason) { }

    private static LoginService service(UserRepository users, List<String> adminEmails) {
        return new LoginService(users, new AuthProperties(adminEmails));
    }
}
