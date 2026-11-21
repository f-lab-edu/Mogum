package com.mogum.course.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.mogum.auth.AccountPrincipal;
import com.mogum.common.api.ApiResponse;
import com.mogum.course.dto.RoadmapListResponse;
import com.mogum.course.dto.RoadmapResponse;
import com.mogum.course.service.RoadmapReadService;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class RoadmapController {
	private final RoadmapReadService roadmapReadService;

	@GetMapping("/api/roadmaps")
	public ApiResponse<RoadmapListResponse> list(
		@AuthenticationPrincipal
		AccountPrincipal.Member member) {
		return ApiResponse.of(roadmapReadService.list(member.userId()));
	}

	@GetMapping("/api/roadmaps/{id}")
	public ApiResponse<RoadmapResponse> read(
		@AuthenticationPrincipal
		AccountPrincipal.Member member,
		@PathVariable
		long id) {
		return ApiResponse.of(roadmapReadService.read(member.userId(), id));
	}
}
