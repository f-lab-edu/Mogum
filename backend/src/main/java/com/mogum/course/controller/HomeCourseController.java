package com.mogum.course.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mogum.auth.AccountPrincipal;
import com.mogum.common.api.ApiResponse;
import com.mogum.course.dto.HomeContinueLearningResponse;
import com.mogum.course.dto.HomeTodoResponse;
import com.mogum.course.service.HomeCourseReadService;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class HomeCourseController {
	private final HomeCourseReadService homeCourseReadService;

	@GetMapping("/api/home/continue-learning")
	public ApiResponse<HomeContinueLearningResponse> continueLearning(
		@AuthenticationPrincipal
		AccountPrincipal.Member member) {
		return ApiResponse.of(homeCourseReadService.continueLearning(member.userId()));
	}

	@GetMapping("/api/home/todos")
	public ApiResponse<HomeTodoResponse> todos(
		@AuthenticationPrincipal
		AccountPrincipal.Member member) {
		return ApiResponse.of(homeCourseReadService.todos(member.userId()));
	}
}
