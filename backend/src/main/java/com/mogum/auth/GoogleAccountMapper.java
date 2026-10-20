package com.mogum.auth;

import com.mogum.account.domain.ExternalIdentity;
import com.mogum.account.service.ExternalAccount;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

public final class GoogleAccountMapper {
    public ExternalAccount map(Authentication authentication) {
        if (!(authentication instanceof OAuth2AuthenticationToken oauth) || !oauth.isAuthenticated()
                || !"google".equals(oauth.getAuthorizedClientRegistrationId())) {
            throw invalidAccount();
        }
        var principal = oauth.getPrincipal();
        Object sub = principal.getAttribute("sub");
        Object email = principal.getAttribute("email");
        if (!(sub instanceof String subject) || subject.isBlank()
                || !(email instanceof String address) || address.isBlank()) {
            throw invalidAccount();
        }
        return new ExternalAccount(new ExternalIdentity("google", subject), address,
                Boolean.TRUE.equals(principal.getAttribute("email_verified")));
    }

    private static OAuth2AuthenticationException invalidAccount() {
        return new OAuth2AuthenticationException(new OAuth2Error("invalid_user_info"));
    }
}
