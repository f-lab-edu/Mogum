package com.mogum.api.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class DiscardingOAuth2AuthorizedClientsTest {
    @Test
    void 토큰은_서비스와_웹_저장소_양쪽에서_보관하지_않는다() {
        var clients = new DiscardingOAuth2AuthorizedClients();
        var client = mock(OAuth2AuthorizedClient.class);
        var authentication = mock(Authentication.class);
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        clients.saveAuthorizedClient(client, authentication);
        clients.saveAuthorizedClient(client, authentication, request, response);
        assertNull(clients.loadAuthorizedClient("google", "sub"));
        assertNull(clients.loadAuthorizedClient("google", authentication, request));
        clients.removeAuthorizedClient("google", "sub");
        clients.removeAuthorizedClient("google", authentication, request, response);
        assertNull(request.getSession(false));
        verifyNoInteractions(client, authentication);
    }
}
