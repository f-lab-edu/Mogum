package com.mogum.account.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mogum.account.domain.CreditEntry;
import com.mogum.account.domain.CreditEntryType;
import com.mogum.account.domain.User;
import com.mogum.account.repository.CreditEntryRepository;
import com.mogum.account.repository.UserRepository;
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.common.time.SeoulClock;

import lombok.RequiredArgsConstructor;

/** 크레딧 잔액과 기록을 호출자의 트랜잭션에 참여해 함께 바꾼다. */
@Service
@Transactional
@RequiredArgsConstructor
public class CreditLedger {
	private final UserRepository userRepository;
	private final CreditEntryRepository creditEntryRepository;

	/** users 행을 트랜잭션이 끝날 때까지 잠근다. 받기가 건 수를 세기 전에 부른다. */
	public void lock(long userId) {
		lockedUser(userId);
	}

	/** amount는 양수다. users 행을 잠그고 잔액과 음수 CONSUME 기록을 함께 바꾼다. */
	public void consume(long userId, long jobId, int amount) {
		if (amount <= 0)
			throw new IllegalArgumentException("amount must be positive");

		var user = lockedUser(userId);
		if (user.getCreditBalance() < amount) {
			var refillDate = user.getCreditRefillAt().atZone(SeoulClock.ZONE).toLocalDate();
			throw ApiException.withMessage(ErrorCode.CREDIT_INSUFFICIENT,
				"크레딧이 부족합니다. " + refillDate + "에 다시 채워집니다");
		}

		user.consumeCredits(amount);
		creditEntryRepository.save(CreditEntry.consume(user, jobId, amount));
	}

	/**
	 * 성공하지 않은 작업에 호출한다. 마지막 기록이 마지막 REFILL 뒤의 CONSUME일 때만 복구한다.
	 * 같은 사용자의 기록은 users 행 잠금 안에서 쓰므로, 시각 대신 증가하는 id로 앞뒤를 가른다.
	 * 따라서 created_at이 같아도 최신 작업 기록과 주기 경계를 구별한다.
	 */
	public void restoreIfOwed(long userId, long jobId) {
		var user = lockedUser(userId);
		var last = creditEntryRepository.findFirstByUserIdAndJobIdOrderByIdDesc(userId, jobId);
		if (last.isEmpty() || last.get().getType() != CreditEntryType.CONSUME)
			return;
		var refill = creditEntryRepository.findFirstByUserIdAndTypeOrderByIdDesc(userId, CreditEntryType.REFILL);
		if (refill.isEmpty() || last.get().getId() <= refill.get().getId())
			return;

		int amount = Math.negateExact(last.get().getAmount());
		user.restoreCredits(amount);
		creditEntryRepository.save(CreditEntry.restore(user, jobId, amount));
	}

	private User lockedUser(long userId) {
		return userRepository.findByIdForUpdate(userId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}
}
