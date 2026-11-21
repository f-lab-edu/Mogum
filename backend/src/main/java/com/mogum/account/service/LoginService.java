package com.mogum.account.service;

import org.springframework.stereotype.Service;

import com.mogum.account.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class LoginService {
	private final UserRepository userRepository;

	public LoginResult login(ExternalAccount account) {
		// 미인증 거절은 DB 접근 전에 한다. 신청 전에는 사용자 행을 만들지 않는다(ADR-154).
		if (!account.emailVerified()) {
			return new LoginResult.Rejected(LoginResult.RejectionReason.EMAIL_NOT_VERIFIED);
		}

		// 인증된 이메일만 저장소에서 조회한다.
		return userRepository.findByProviderAndSubject(account.provider(), account.subject())
			.<LoginResult>map(user -> new LoginResult.Success(user.getId()))
			.orElseGet(() -> new LoginResult.NotApplied(account));
	}
}
