package com.mogum.account.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mogum.account.service.WithdrawService;
import com.mogum.auth.AccountPrincipal;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class WithdrawController {
	private final WithdrawService withdrawService;

	@PostMapping("/api/account/withdraw")
	public ResponseEntity<Void> withdraw(
		@AuthenticationPrincipal
		AccountPrincipal.Member principal,
		HttpServletRequest request) {
		withdrawService.withdraw(principal.userId());
		// 커밋 뒤 무효화해야 요청 끝에 Spring Session이 현재 세션을 다시 저장하지 않는다.
		var session = request.getSession(false);
		if (session != null) {
			session.invalidate();
		}
		return ResponseEntity.noContent().build();
	}
}
