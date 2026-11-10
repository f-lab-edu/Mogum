package com.mogum.account.controller;

import com.mogum.account.dto.MeResponse;
import com.mogum.account.service.ApplyService;
import com.mogum.auth.AccountPrincipal;
import com.mogum.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ApplyController {
    private final ApplyService applications;

    public ApplyController(ApplyService applications) {
        this.applications = applications;
    }

    @PostMapping("/api/account/apply")
    public ApiResponse<MeResponse> apply(@AuthenticationPrincipal AccountPrincipal principal,
                                         HttpServletRequest request) {
        var member = applications.apply(principal);
        // 서비스의 트랜잭션이 커밋된 뒤에만 신원 세션을 회원 세션으로 바꾼다.
        if (principal instanceof AccountPrincipal.NotApplied) {
            var session = request.getSession(false);
            request.changeSessionId();
            java.util.Collections.list(session.getAttributeNames()).forEach(session::removeAttribute);
            session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME,
                    Long.toString(member.userId()));
        }
        return ApiResponse.of(new MeResponse(member.status().name(), member.email(), member.name()));
    }
}
