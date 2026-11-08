package com.mogum.account.service;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.domain.UserStatus;
import com.mogum.account.repository.FakeUserRepository;
import com.mogum.account.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class LoginServiceTest {
    @Mock private UserRepository untouchedUsers;

    @Test
    @DisplayName("이메일 미인증은 DB 접근 전에 거절한다")
    void rejectsUnverifiedEmailBeforeQueryingDatabase() {
        // given
        var login = new LoginService(untouchedUsers);

        // when
        var result = login.login(new ExternalAccount("google", "sub", "me@example.com", "Google name", false));

        // then
        assertThat(result).isEqualTo(new LoginResult.Rejected(LoginResult.RejectionReason.EMAIL_NOT_VERIFIED));
        then(untouchedUsers).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("저장된 신원이 없으면 행을 만들지 않고 신청 전 신원을 반환한다")
    void returnsNotAppliedWithoutCreatingUser() {
        // given
        var users = new FakeUserRepository();
        var login = new LoginService(users);
        var account = new ExternalAccount("google", "new-sub", "any@example.com", "Google name", true);

        // when
        var result = login.login(account);

        // then
        assertThat(result).isEqualTo(new LoginResult.NotApplied(account));
        assertThat(users.count()).isZero();
    }

    @ParameterizedTest(name = "기존 회원 상태 {0}")
    @EnumSource(UserStatus.class)
    @DisplayName("재로그인은 상태와 관계없이 기존 ID를 반환하고 저장된 신원을 보존한다")
    void returnsExistingUserIdOnRelogin(UserStatus status) {
        // given
        var users = new FakeUserRepository();
        var existing = users.save(UserFixtures.withStatus(UserFixtures.withId(
                UserFixtures.user("google", "stable-sub", "old@example.com"), 12L), status));
        var login = new LoginService(users);

        // when
        var result = login.login(new ExternalAccount("google", "stable-sub", "changed@example.com", "Google name", true));

        // then
        assertThat(result).isEqualTo(new LoginResult.Success(existing.getId()));
        assertThat(users.count()).isEqualTo(1);
        assertThat(users.findById(existing.getId())).containsSame(existing);
        assertThat(existing.getEmail()).isEqualTo("old@example.com");
        assertThat(existing.getStatus()).isEqualTo(status);
    }

    @ParameterizedTest(name = "제공자 [{0}] 식별자 [{1}]")
    @CsvSource({"other-provider, shared", "Google, shared", "google, Shared", "google, other-sub"})
    @DisplayName("같은 이메일이어도 제공자와 식별자가 정확히 일치하지 않으면 신청 전이다")
    void requiresExactIdentityMatch(String provider, String subject) {
        // given
        var users = new FakeUserRepository();
        users.save(UserFixtures.user("google", "shared", "shared@example.com"));
        var login = new LoginService(users);
        var account = new ExternalAccount(provider, subject, "shared@example.com", "Google name", true);

        // when
        var result = login.login(account);

        // then
        assertThat(result).isEqualTo(new LoginResult.NotApplied(account));
        assertThat(users.count()).isEqualTo(1);
        assertThat(users.findByProviderAndSubject(provider, subject)).isEmpty();
    }
}
