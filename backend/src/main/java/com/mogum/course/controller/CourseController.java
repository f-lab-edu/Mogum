package com.mogum.course.controller;

import com.mogum.auth.AccountPrincipal;
import com.mogum.common.api.ApiResponse;
import com.mogum.course.dto.CourseListResponse;
import com.mogum.course.dto.CourseResponse;
import com.mogum.course.dto.CourseTitleRequest;
import com.mogum.course.service.CourseCommandService;
import com.mogum.course.service.CourseReadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class CourseController {
    private final CourseReadService courseReadService;
    private final CourseCommandService courseCommandService;

    @GetMapping("/api/courses")
    public ApiResponse<CourseListResponse> list(@AuthenticationPrincipal AccountPrincipal.Member member) {
        return ApiResponse.of(courseReadService.list(member.userId()));
    }

    @GetMapping("/api/courses/{id}")
    public ApiResponse<CourseResponse> read(@AuthenticationPrincipal AccountPrincipal.Member member,
            @PathVariable long id) {
        return ApiResponse.of(courseReadService.read(member.userId(), id));
    }

    @PostMapping("/api/courses/{id}/start")
    public ApiResponse<CourseResponse> start(@AuthenticationPrincipal AccountPrincipal.Member member,
            @PathVariable long id) {
        return ApiResponse.of(courseCommandService.start(member.userId(), id));
    }

    @PatchMapping("/api/courses/{id}")
    public ApiResponse<CourseResponse> rename(@AuthenticationPrincipal AccountPrincipal.Member member,
            @PathVariable long id, @Valid @RequestBody CourseTitleRequest request) {
        return ApiResponse.of(courseCommandService.rename(member.userId(), id, request.title()));
    }
}
