package com.mogum.account.service;

import com.mogum.account.domain.CreditEntry;
import com.mogum.account.domain.CreditEntryType;
import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.FakeCreditEntryRepository;
import com.mogum.account.repository.FakeUserRepository;
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreditLedgerTest {
    private final FakeUserRepository userRepository = new FakeUserRepository();
    private final FakeCreditEntryRepository creditEntryRepository = new FakeCreditEntryRepository();
    private final CreditLedger creditLedger = new CreditLedger(userRepository, creditEntryRepository);

    @Test
    @DisplayName("잔액만큼 쓰면 잔액은 0이고 작업의 음수 씀 기록을 남긴다")
    void consumesBalanceAndRecordsJob() {
        // given
        var user = userRepository.save(
                UserFixtures.approved("me@example.com", 12, Instant.parse("2026-12-07T19:00:00Z")));
        creditEntryRepository.save(CreditEntry.refill(user, 12));

        // when
        creditLedger.consume(user.getId(), 71L, 12);

        // then
        assertThat(user.getCreditBalance()).isZero();
        assertThat(creditEntryRepository.entries()).last().satisfies(entry -> {
            assertThat(entry.getUser()).isSameAs(user);
            assertThat(entry.getJobId()).isEqualTo(71L);
            assertThat(entry.getType()).isEqualTo(CreditEntryType.CONSUME);
            assertThat(entry.getAmount()).isEqualTo(-12);
        });
        assertThat(creditEntryRepository.entries().stream().mapToInt(CreditEntry::getAmount).sum()).isZero();
    }

    @Test
    @DisplayName("잔액이 부족하면 변경 없이 한국 새벽 4시의 채움 날짜를 문구에 넣는다")
    void rejectsInsufficientCreditWithSeoulRefillDate() {
        // given
        var user = userRepository.save(
                UserFixtures.approved("me@example.com", 3, Instant.parse("2026-12-07T19:00:00Z")));
        creditEntryRepository.save(CreditEntry.refill(user, 3));

        // when & then
        assertThatThrownBy(() -> creditLedger.consume(user.getId(), 71L, 4))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.CREDIT_INSUFFICIENT);
                    assertThat(exception.responseMessage()).isEqualTo("크레딧이 부족합니다. 2026-12-08에 다시 채워집니다");
                });
        assertThat(user.getCreditBalance()).isEqualTo(3);
        assertThat(creditEntryRepository.entries()).hasSize(1);
    }

    @Test
    @DisplayName("마지막 채움 뒤 작업의 마지막 씀 크기를 복구한다")
    void restoresLatestConsumeAfterRefill() {
        // given
        var user = userRepository.save(
                UserFixtures.approved("me@example.com", 10, Instant.parse("2026-12-07T19:00:00Z")));
        creditEntryRepository.save(CreditEntry.refill(user, 10));

        creditLedger.consume(user.getId(), 71L, 2);
        creditLedger.restoreIfOwed(user.getId(), 71L);

        creditLedger.consume(user.getId(), 71L, 3);

        // when
        creditLedger.restoreIfOwed(user.getId(), 71L);

        // then
        assertThat(user.getCreditBalance()).isEqualTo(10);
        assertThat(creditEntryRepository.entries()).last().satisfies(entry -> {
            assertThat(entry.getJobId()).isEqualTo(71L);
            assertThat(entry.getType()).isEqualTo(CreditEntryType.RESTORE);
            assertThat(entry.getAmount()).isEqualTo(3);
        });
    }

    @Test
    @DisplayName("이미 복구한 작업은 다시 복구하지 않는다")
    void skipsAlreadyRestoredJob() {
        // given
        var user = userRepository.save(
                UserFixtures.approved("me@example.com", 10, Instant.parse("2026-12-07T19:00:00Z")));
        creditEntryRepository.save(CreditEntry.refill(user, 10));

        creditLedger.consume(user.getId(), 71L, 3);
        creditLedger.restoreIfOwed(user.getId(), 71L);

        // when
        creditLedger.restoreIfOwed(user.getId(), 71L);

        // then
        assertThat(user.getCreditBalance()).isEqualTo(10);
        assertThat(creditEntryRepository.entries()).hasSize(3);
    }

    @Test
    @DisplayName("마지막 채움 앞의 씀은 복구하지 않는다")
    void skipsConsumeBeforeLatestRefill() {
        // given
        var user = userRepository.save(
                UserFixtures.approved("me@example.com", 10, Instant.parse("2026-12-07T19:00:00Z")));
        creditEntryRepository.save(CreditEntry.refill(user, 10));

        creditLedger.consume(user.getId(), 71L, 3);
        creditEntryRepository.save(CreditEntry.refill(user, 0));

        // when
        creditLedger.restoreIfOwed(user.getId(), 71L);

        // then
        assertThat(user.getCreditBalance()).isEqualTo(7);
        assertThat(creditEntryRepository.entries()).hasSize(3);
    }

    @Test
    @DisplayName("사용자와 작업에 해당하는 기록이 없으면 다른 작업이나 사용자의 씀을 복구하지 않는다")
    void scopesRestoreToUserAndJob() {
        // given
        var user = userRepository.save(
                UserFixtures.approved("me@example.com", 10, Instant.parse("2026-12-07T19:00:00Z")));
        var other = userRepository.save(
                UserFixtures.approved("other@example.com", 10, Instant.parse("2026-12-07T19:00:00Z")));
        creditEntryRepository.save(CreditEntry.refill(user, 10));
        creditEntryRepository.save(CreditEntry.refill(other, 10));

        creditLedger.consume(user.getId(), 72L, 3);
        creditLedger.consume(other.getId(), 71L, 2);

        // when
        creditLedger.restoreIfOwed(user.getId(), 71L);

        // then
        assertThat(user.getCreditBalance()).isEqualTo(7);
        assertThat(other.getCreditBalance()).isEqualTo(8);
        assertThat(creditEntryRepository.entries()).hasSize(4);
    }

    @ParameterizedTest(name = "크기 {0}")
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    @DisplayName("양수가 아닌 씀 크기는 잔액과 기록을 바꾸기 전에 거절한다")
    void rejectsNonPositiveConsume(int amount) {
        // given
        var user = userRepository.save(
                UserFixtures.approved("me@example.com", 10, Instant.parse("2026-12-07T19:00:00Z")));

        // when & then
        assertThatThrownBy(() -> creditLedger.consume(user.getId(), 71L, amount))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(user.getCreditBalance()).isEqualTo(10);
        assertThat(creditEntryRepository.entries()).isEmpty();
    }
}
