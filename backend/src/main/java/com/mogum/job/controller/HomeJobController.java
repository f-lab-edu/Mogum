package com.mogum.job.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mogum.auth.AccountPrincipal;
import com.mogum.common.api.ApiResponse;
import com.mogum.job.dto.HomeJobResponse;
import com.mogum.job.service.JobReadService;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class HomeJobController {
	private final JobReadService jobReadService;

	@GetMapping("/api/home/jobs")
	public ApiResponse<HomeJobResponse> jobs(
		@AuthenticationPrincipal
		AccountPrincipal.Member member) {
		return ApiResponse.of(new HomeJobResponse(jobReadService.unfinished(member.userId())));
	}
}
