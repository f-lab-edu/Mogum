package com.mogum.account.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CreditEntryTest {
    @Test
    @DisplayName("채움 기록은 회원과 금액을 보존하고 REFILL로 시작한다")
    void createsRefillEntryWithUserAndAmount() {
        // given
        var user = UserFixtures.user("me@example.com");

        // when
        var entry = CreditEntry.refill(user, 123);

        // then
        assertThat(entry.getId()).isNull();
        assertThat(entry.getCreatedAt()).isNull();
        assertThat(entry.getUpdatedAt()).isNull();
        assertThat(entry.getUser()).isSameAs(user);
        assertThat(entry.getType()).isEqualTo(CreditEntryType.REFILL);
        assertThat(entry.getAmount()).isEqualTo(123);
    }
}
