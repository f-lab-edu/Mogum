package com.mogum.auth;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecurityConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = {"id", "secret"})
    void Google_필수_자격_정보가_비어_있으면_기동을_거부한다(String missing) {
        var google = mock(ClientRegistration.class);
        when(google.getClientId()).thenReturn(missing.equals("id") ? "" : "test-client");
        when(google.getClientSecret()).thenReturn(missing.equals("secret") ? "" : "test-secret");
        var registrations = mock(ClientRegistrationRepository.class);
        when(registrations.findByRegistrationId("google")).thenReturn(google);
        assertThrows(IllegalArgumentException.class,
                () -> new SecurityConfiguration().discardedGoogleTokens(registrations));
    }
}
