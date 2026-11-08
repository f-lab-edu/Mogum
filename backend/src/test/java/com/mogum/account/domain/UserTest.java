package com.mogum.account.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserTest {
    @Test
    @DisplayName("첫 로그인 회원은 식별자와 역할을 보존하고 ACTIVE로 시작하며 ID와 약관이 없다")
    void createsActiveUserWithoutIdOrTerms() {
        // when
        var user = User.join("google", "sub-1", "me@example.com", UserRole.ADMIN);

        // then
        assertThat(user.getId()).isNull();
        assertThat(user.getCreatedAt()).isNull();
        assertThat(user.getUpdatedAt()).isNull();
        assertThat(user.getProvider()).isEqualTo("google");
        assertThat(user.getSubject()).isEqualTo("sub-1");
        assertThat(user.getEmail()).isEqualTo("me@example.com");
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(user.getTermsAgreedAt()).isNull();
        assertThat(user.getTermsVersion()).isNull();
    }
}
