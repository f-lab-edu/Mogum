package com.mogum.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class SecurityConfigurationTest {
    // ID 검사에서 먼저 종료해도 다른 자격 정보가 유효하다는 입력을 명시한다.
    @Mock(strictness = Mock.Strictness.LENIENT) private ClientRegistration google;
    @Mock private ClientRegistrationRepository registrations;

    @ParameterizedTest(name = "누락 자격 정보 {0}")
    @ValueSource(strings = {"id", "secret"})
    @DisplayName("Google 필수 자격 정보가 비어 있으면 기동을 거부한다")
    void rejectsMissingGoogleCredentials(String missing) {
        // given
        given(google.getClientId()).willReturn(missing.equals("id") ? "" : "test-client");
        given(google.getClientSecret()).willReturn(missing.equals("secret") ? "" : "test-secret");
        given(registrations.findByRegistrationId("google")).willReturn(google);
        var configuration = new SecurityConfiguration();

        // when & then
        assertThatThrownBy(() -> configuration.discardedGoogleTokens(registrations))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
