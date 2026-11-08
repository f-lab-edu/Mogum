package com.mogum.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class DiscardingOAuth2AuthorizedClientsTest {
    @Mock private OAuth2AuthorizedClient client;
    @Mock private Authentication authentication;
    private final DiscardingOAuth2AuthorizedClients clients = new DiscardingOAuth2AuthorizedClients();

    @Test
    @DisplayName("서비스의 저장은 토큰과 인증 정보를 읽지 않는다")
    void discardsServiceSaveWithoutReadingTokens() {
        // when
        clients.saveAuthorizedClient(client, authentication);

        // then
        then(client).shouldHaveNoInteractions();
        then(authentication).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("웹 저장소의 저장은 토큰을 읽거나 세션을 만들지 않는다")
    void discardsWebSaveWithoutReadingTokensOrCreatingSession() {
        // given
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();

        // when
        clients.saveAuthorizedClient(client, authentication, request, response);

        // then
        assertThat(request.getSession(false)).isNull();
        then(client).shouldHaveNoInteractions();
        then(authentication).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("서비스는 저장을 요청한 뒤에도 토큰을 반환하지 않는다")
    void returnsNoServiceTokensAfterSave() {
        // given
        clients.saveAuthorizedClient(client, authentication);

        // when
        OAuth2AuthorizedClient loaded = clients.loadAuthorizedClient("google", "sub");

        // then
        assertThat(loaded).isNull();
        then(client).shouldHaveNoInteractions();
        then(authentication).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("웹 저장소는 저장을 요청한 뒤에도 토큰을 반환하지 않고 세션을 만들지 않는다")
    void returnsNoWebTokensAfterSave() {
        // given
        var request = new MockHttpServletRequest();
        clients.saveAuthorizedClient(client, authentication, request, new MockHttpServletResponse());

        // when
        OAuth2AuthorizedClient loaded = clients.loadAuthorizedClient("google", authentication, request);

        // then
        assertThat(loaded).isNull();
        assertThat(request.getSession(false)).isNull();
        then(client).shouldHaveNoInteractions();
        then(authentication).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("서비스의 삭제는 저장을 요청한 토큰과 인증 정보를 읽지 않는다")
    void removesServiceTokensWithoutReadingCredentials() {
        // given
        clients.saveAuthorizedClient(client, authentication);

        // when
        clients.removeAuthorizedClient("google", "sub");

        // then
        then(client).shouldHaveNoInteractions();
        then(authentication).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("웹 저장소의 삭제는 인증 정보를 읽거나 세션을 만들지 않는다")
    void removesWebTokensWithoutReadingCredentialsOrCreatingSession() {
        // given
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        clients.saveAuthorizedClient(client, authentication, request, response);

        // when
        clients.removeAuthorizedClient("google", authentication, request, response);

        // then
        assertThat(request.getSession(false)).isNull();
        then(client).shouldHaveNoInteractions();
        then(authentication).shouldHaveNoInteractions();
    }
}
