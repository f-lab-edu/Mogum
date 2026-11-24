package com.mogum.card.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mogum.auth.AccountPrincipal;
import com.mogum.card.dto.TodayReviewResponse;
import com.mogum.card.service.TodayReviewService;
import com.mogum.common.api.ApiResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class ReviewController {
	private final TodayReviewService todayReviewService;

	@GetMapping("/api/reviews/today")
	public ApiResponse<TodayReviewResponse> read(
		@AuthenticationPrincipal
		AccountPrincipal.Member member,
		@RequestParam(required = false)
		Long courseId) {
		return ApiResponse.of(todayReviewService.read(member.userId(), courseId));
	}
}
