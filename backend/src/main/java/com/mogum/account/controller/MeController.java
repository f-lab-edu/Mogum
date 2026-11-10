package com.mogum.account.controller;

import com.mogum.auth.AccountPrincipal;
import com.mogum.account.dto.MeResponse;
import com.mogum.common.api.ApiResponse;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {
    @GetMapping("/api/me")
    public ApiResponse<MeResponse> me(@AuthenticationPrincipal AccountPrincipal principal) {
        String status = switch (principal) {
            case AccountPrincipal.Member member -> member.status().name();
            case AccountPrincipal.NotApplied ignored -> "NOT_APPLIED";
        };
        return ApiResponse.of(new MeResponse(status, principal.email(), principal.name()));
    }
}
