package com.mogum.job.controller;

import com.mogum.auth.AccountPrincipal;
import com.mogum.common.api.ApiResponse;
import com.mogum.job.dto.JobReceiveRequest;
import com.mogum.job.dto.JobResponse;
import com.mogum.job.service.JobIntakeService;
import com.mogum.job.service.JobReadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class JobController {
    private final JobIntakeService jobIntakeService;
    private final JobReadService jobReadService;

    @PostMapping("/api/jobs")
    public ApiResponse<JobResponse> receive(@AuthenticationPrincipal AccountPrincipal.Member member,
                                            @Valid @RequestBody JobReceiveRequest request) {
        var result = jobIntakeService.receive(member.userId(), request.toJobRequest());
        return ApiResponse.of(jobReadService.read(member.userId(), result.id()));
    }

    @GetMapping("/api/jobs/{id}")
    public ApiResponse<JobResponse> read(@AuthenticationPrincipal AccountPrincipal.Member member, @PathVariable long id) {
        return ApiResponse.of(jobReadService.read(member.userId(), id));
    }

    @PostMapping("/api/jobs/{id}/retry")
    public ApiResponse<JobResponse> retry(@AuthenticationPrincipal AccountPrincipal.Member member, @PathVariable long id) {
        var result = jobIntakeService.retry(member.userId(), id);
        return ApiResponse.of(jobReadService.read(member.userId(), result.id()));
    }

    @PostMapping("/api/jobs/{id}/discard")
    public ResponseEntity<Void> discard(@AuthenticationPrincipal AccountPrincipal.Member member, @PathVariable long id) {
        jobIntakeService.discard(member.userId(), id);
        return ResponseEntity.noContent().build();
    }
}
