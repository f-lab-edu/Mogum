package com.mogum.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.domain.UserStatus;
import com.mogum.account.repository.FakeCreditEntryRepository;
import com.mogum.account.repository.FakeUserRepository;
import com.mogum.auth.AccountPrincipal;
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;

class MeReadServiceTest {
	private final FakeUserRepository userRepository = new FakeUserRepository();
	private final MeReadService meReadService = new MeReadService(userRepository);

	@ParameterizedTest(name = "DB 회원 상태 {0}")
	@EnumSource(UserStatus.class)
	@DisplayName("회원은 인증 정보 대신 DB의 상태와 신원을 읽고 승인된 경우에만 크레딧을 싣는다")
	void readsCurrentUserAndOnlyApprovedCredit(UserStatus status) {
		// given
		var user = userRepository.save(status == UserStatus.APPROVED
			? UserFixtures.approved("current@example.com", 17, Instant.parse("2026-12-07T19:00:00Z"))
			: UserFixtures.withStatus(UserFixtures.user("current@example.com"), status));
		var stale = new AccountPrincipal.Member(user.getId(), "old@example.com", "옛 이름", UserStatus.REJECTED);

		// when
		var result = meReadService.read(stale);

		// then
		assertThat(result.status()).isEqualTo(status.name());
		assertThat(result.email()).isEqualTo("current@example.com");
		assertThat(result.name()).isEqualTo("current");
		if (status == UserStatus.APPROVED) {
			assertThat(result.credit().balance()).isEqualTo(user.getCreditBalance());
			assertThat(result.credit().nextRefillDate()).isEqualTo("2026-12-08");
		} else {
			assertThat(result.credit()).isNull();
		}
	}

	@Test
	@DisplayName("신청 전에는 세션 신원만 반환하고 회원을 만들지 않는다")
	void readsNotAppliedIdentityWithoutCreatingUser() {
		// given
		var identity = new AccountPrincipal.NotApplied("google", "sub", "new@example.com", "새 이름");

		// when
		var result = meReadService.read(identity);

		// then
		assertThat(result.status()).isEqualTo("NOT_APPLIED");
		assertThat(result.email()).isEqualTo(identity.email());
		assertThat(result.name()).isEqualTo(identity.name());
		assertThat(result.credit()).isNull();
		assertThat(userRepository.count()).isZero();
	}

	@Test
	@DisplayName("회원이 사라지면 인증 오류다")
	void rejectsMissingUser() {
		// given
		var member = new AccountPrincipal.Member(7L, "me@example.com", "이름", UserStatus.APPROVED);

		// when & then
		assertThatThrownBy(() -> meReadService.read(member)).isInstanceOfSatisfying(ApiException.class,
			exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
	}

	@Test
	@DisplayName("크레딧 부족 문구와 내 정보의 다음 채움 날짜는 같다")
	void sharesRefillDateWithInsufficientCreditMessage() {
		// given
		var user = userRepository.save(
			UserFixtures.approved("me@example.com", 0, Instant.parse("2026-12-31T19:00:00Z")));
		var member = new AccountPrincipal.Member(user.getId(), user.getEmail(), user.getName(), user.getStatus());
		var date = meReadService.read(member).credit().nextRefillDate();
		var ledger = new CreditLedger(userRepository, new FakeCreditEntryRepository());

		// when & then
		assertThatThrownBy(() -> ledger.consume(user.getId(), 71L, 1))
			.isInstanceOfSatisfying(ApiException.class, exception -> {
				assertThat(date).isEqualTo("2027-01-01");
				assertThat(exception.responseMessage()).isEqualTo("크레딧이 부족합니다. " + date + "에 다시 채워집니다");
			});
	}
}
