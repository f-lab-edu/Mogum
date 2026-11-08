package com.mogum.auth;

import com.mogum.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.ObjectMapper;

import static com.mogum.support.IntegrationTestConfiguration.FakeGoogle.*;
import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class OAuthLoginHandlerPkceIntegrationTest extends OAuthScenario {
    @Test
    @DisplayName("형식이 유효해도 바뀐 verifier는 가짜 Google이 거절한다")
    void rejectsChangedVerifierAtProvider() throws Exception {
        // given
        var start = start(null);
        var modified = withVerifier(start, "a".repeat(64));
        // when
        var response = callback(modified, "ok", null);
        // then
        assertFailure(response, "login_failed");
        assertThat(TOKEN_CALLS.get()).isEqualTo(1);
    }

    @ParameterizedTest(name = "조건 {0}")
    @ValueSource(strings = {"missing", "short", "long", "invalid-character"})
    @DisplayName("없거나 형식이 틀린 verifier는 토큰 교환 전에 실패한다")
    void rejectsMalformedVerifierBeforeTokenExchange(String condition) throws Exception {
        // given
        var start = start(null);
        String verifier = switch (condition) {
            case "missing" -> null;
            case "short" -> "a".repeat(42);
            case "long" -> "a".repeat(129);
            default -> "a".repeat(43) + "!";
        };
        var modified = withVerifier(start, verifier);
        // when
        var response = callback(modified, "ok", null);
        // then
        assertFailure(response, "login_failed");
        assertThat(TOKEN_CALLS.get()).isEqualTo(0);
    }

    @Test
    @DisplayName("가짜 Google도 verifier가 없는 토큰 요청을 거절한다")
    void fakeProviderRejectsMissingVerifier() throws Exception {
        // given
        start(null);
        // when
        var response = RestTestClient.bindToServer().baseUrl(ORIGIN).build().post().uri("/token")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body("code=ok&grant_type=authorization_code").exchange().returnResult(String.class);
        // then
        assertThat(response.getStatus().value()).isEqualTo(400);
        assertThat(new ObjectMapper().readTree(response.getResponseBody()).get("error").asString()).isEqualTo("invalid_grant");
        assertThat(count("users")).isEqualTo(usersBefore);
        assertThat(count("spring_session")).isEqualTo(sessionsBefore);
    }
}
