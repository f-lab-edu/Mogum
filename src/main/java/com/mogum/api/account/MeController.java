package com.mogum.api.account;

import com.mogum.core.account.User;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {
    @GetMapping("/api/me")
    public MeResponse me(@AuthenticationPrincipal User user) {
        return new MeResponse(user.email(), user.status(), user.role(), user.termsAgreedAt() != null, null);
    }
}
