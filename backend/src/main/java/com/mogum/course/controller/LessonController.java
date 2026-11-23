package com.mogum.course.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mogum.auth.AccountPrincipal;
import com.mogum.common.api.ApiResponse;
import com.mogum.course.dto.LessonCompletionResponse;
import com.mogum.course.dto.LessonResponse;
import com.mogum.course.service.LessonCommandService;
import com.mogum.course.service.LessonReadService;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class LessonController {
	private final LessonReadService lessonReadService;
	private final LessonCommandService lessonCommandService;

	@GetMapping("/api/lessons/{id}")
	public ApiResponse<LessonResponse> read(
		@AuthenticationPrincipal
		AccountPrincipal.Member member,
		@PathVariable
		long id) {
		return ApiResponse.of(lessonReadService.read(member.userId(), id));
	}

	@PostMapping("/api/lessons/{id}/complete")
	public ApiResponse<LessonCompletionResponse> complete(
		@AuthenticationPrincipal
		AccountPrincipal.Member member,
		@PathVariable
		long id) {
		return ApiResponse.of(lessonCommandService.complete(member.userId(), id));
	}
}
