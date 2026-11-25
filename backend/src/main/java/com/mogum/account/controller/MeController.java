package com.mogum.account.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mogum.account.dto.MeResponse;
import com.mogum.account.service.MeReadService;
import com.mogum.auth.AccountPrincipal;
import com.mogum.common.api.ApiResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class MeController {
	private final MeReadService meReadService;

	@GetMapping("/api/me")
	public ApiResponse<MeResponse> me(
		@AuthenticationPrincipal
		AccountPrincipal principal) {
		return ApiResponse.of(meReadService.read(principal));
	}
}
