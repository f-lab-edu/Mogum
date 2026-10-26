package com.mogum.account.controller;

import com.mogum.account.domain.User;
import com.mogum.account.dto.MeResponse;
import com.mogum.common.ApiResponse;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {
    @GetMapping("/api/me")
    public ApiResponse<MeResponse> me(@AuthenticationPrincipal User user) {
        return ApiResponse.of(new MeResponse(user.getEmail(), user.getStatus(), user.getRole(),
                user.getTermsAgreedAt() != null, null));
    }
}
