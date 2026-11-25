package com.mogum.card.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mogum.auth.AccountPrincipal;
import com.mogum.card.dto.HomeReviewCountResponse;
import com.mogum.card.dto.StreakResponse;
import com.mogum.card.service.StreakReadService;
import com.mogum.card.service.TodayReviewService;
import com.mogum.common.api.ApiResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class HomeReviewController {
	private final TodayReviewService todayReviewService;
	private final StreakReadService streakReadService;

	@GetMapping("/api/home/reviews")
	public ApiResponse<HomeReviewCountResponse> reviews(
		@AuthenticationPrincipal
		AccountPrincipal.Member member) {
		return ApiResponse.of(new HomeReviewCountResponse(todayReviewService.count(member.userId(), null)));
	}

	@GetMapping("/api/home/streak")
	public ApiResponse<StreakResponse> streak(
		@AuthenticationPrincipal
		AccountPrincipal.Member member) {
		return ApiResponse.of(streakReadService.read(member.userId()));
	}
}
