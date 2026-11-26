package com.mogum.account.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mogum.account.repository.JpaAccountWithdrawalRepository;
import com.mogum.account.repository.UserRepository;
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class WithdrawService {
	private final JpaAccountWithdrawalRepository jpaAccountWithdrawalRepository;
	private final UserRepository userRepository;

	public void withdraw(long userId) {
		// 결과 저장·실패 기록과 같은 순서로 모든 작업 행부터 잡고 사용자 행을 잡는다.
		jpaAccountWithdrawalRepository.lockJobs(userId);
		userRepository.findByIdForUpdate(userId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		// 잔액과 기록도 함께 지우므로 크레딧 복구는 하지 않는다.
		jpaAccountWithdrawalRepository.deleteOwnedData(userId);
	}
}
