package com.mogum.account.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mogum.account.domain.UserStatus;
import com.mogum.account.dto.MeResponse;
import com.mogum.account.repository.UserRepository;
import com.mogum.auth.AccountPrincipal;
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.common.time.SeoulClock;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MeReadService {
	private final UserRepository userRepository;

	@Transactional(readOnly = true)
	public MeResponse read(AccountPrincipal principal) {
		if (principal instanceof AccountPrincipal.NotApplied identity) {
			return new MeResponse("NOT_APPLIED", identity.email(), identity.name(), null);
		}

		var member = (AccountPrincipal.Member)principal;
		var user = userRepository.findById(member.userId())
			.orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
		var credit = user.getStatus() == UserStatus.APPROVED
			? new MeResponse.Credit(user.getCreditBalance(),
				SeoulClock.calendarDate(user.getCreditRefillAt()).toString())
			: null;
		return new MeResponse(user.getStatus().name(), user.getEmail(), user.getName(), credit);
	}
}
