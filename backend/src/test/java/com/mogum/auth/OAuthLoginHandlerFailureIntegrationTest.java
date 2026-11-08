package com.mogum.auth;

import com.mogum.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static com.mogum.support.IntegrationTestConfiguration.FakeGoogle.*;
import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class OAuthLoginHandlerFailureIntegrationTest extends OAuthScenario {
    @ParameterizedTest(name = "조건 {0}")
    @ValueSource(strings = {"not-allowed", "unverified", "state", "no-cookie", "corrupt-cookie", "expired-cookie",
            "cancel", "missing-code", "token", "userinfo", "unknown"})
    @DisplayName("로그인 실패는 고정된 분류만 남기고 원문을 남기지 않는다")
    void logsOnlyKnownFailureClassification(String condition, CapturedOutput output) throws Exception {
        // given
        String marker = "LEAKCHECK-oauth-marker";
        var start = start(null);
        String cookie = start.cookie();
        String path = "/auth/google/callback?code=" + marker + "&state=" + start.state();
        String reason;
        String level = "INFO";
        String result = "login_failed";
        switch (condition) {
            case "not-allowed" -> {
                email = marker + "@example.com";
                result = "not_allowed";
                reason = "not_allowed";
            }
            case "unverified" -> {
                email = marker + "@example.com";
                verified = false;
                reason = "email_not_verified";
            }
            case "state", "no-cookie", "corrupt-cookie", "expired-cookie" -> {
                cookie = switch (condition) {
                    case "no-cookie" -> null;
                    case "corrupt-cookie" -> "MOGUM_OAUTH_REQUEST=" + marker;
                    case "expired-cookie" -> expiredCookie(start);
                    default -> start.cookie();
                };
                if (condition.equals("state")) path = "/auth/google/callback?code=" + marker + "&state=" + marker;
                reason = "authorization_request_not_found";
            }
            case "cancel", "unknown" -> {
                path = "/auth/google/callback?error=" + (condition.equals("cancel") ? "access_denied" : marker)
                        + "&error_description=" + marker + "&error_uri=https://example.com/" + marker
                        + "&state=" + start.state();
                reason = condition.equals("cancel") ? "access_denied" : "unknown";
            }
            case "missing-code" -> {
                path = "/auth/google/callback?state=" + start.state() + "&error_description=" + marker;
                reason = "invalid_request";
            }
            case "token", "userinfo" -> {
                tokenFailure = condition.equals("token");
                userInfoFailure = condition.equals("userinfo");
                sub = marker;
                reason = tokenFailure ? "invalid_grant" : "invalid_user_info_response";
                level = "WARN";
            }
            default -> throw new AssertionError(condition);
        }
        // when
        var response = get(path, cookie);
        // then
        assertFailure(response, result);
        assertThat(TOKEN_CALLS.get()).isEqualTo(condition.equals("not-allowed") || condition.equals("unverified")
                || condition.equals("token") || condition.equals("userinfo") ? 1 : 0);
        var line = loginLine(response, output);
        assertThat(line).contains(" " + level + " ");
        assertThat(line.endsWith("login result=" + result + " reason=" + reason)).isTrue();
        assertPrivate(output, marker, email, sub, start.state(), start.cookie(), verifier(start),
                "fake-access-token", "fake-id-token");
    }

    @Test
    @DisplayName("관리자 밖 계정은 행을 남기지 않고 not allowed로 보낸다")
    void rejectsUnlistedAccountWithoutRows() throws Exception {
        // given
        email = "outside@example.com";
        var start = start(null);
        // when
        var response = callback(start, "ok", null);
        // then
        assertFailure(response, "not_allowed");
    }

    @Test
    @DisplayName("이메일 미인증은 행을 남기지 않고 login failed로 보낸다")
    void rejectsUnverifiedEmailWithoutRows() throws Exception {
        // given
        verified = false;
        var start = start(null);
        // when
        var response = callback(start, "ok", null);
        // then
        assertFailure(response, "login_failed");
    }

    @ParameterizedTest(name = "조건 {0}")
    @ValueSource(strings = {"wrong-state", "no-cookie", "corrupt-cookie", "cancel", "missing-code"})
    @DisplayName("유효한 요청이 없는 콜백은 토큰 교환 전에 실패한다")
    void rejectsInvalidCallbackBeforeTokenExchange(String condition) throws Exception {
        // given
        var start = start(null);
        String state = condition.equals("wrong-state") ? "wrong" : start.state();
        String cookie = switch (condition) {
            case "no-cookie" -> null;
            case "corrupt-cookie" -> "MOGUM_OAUTH_REQUEST=broken";
            default -> start.cookie();
        };
        String params = condition.equals("cancel") ? "error=access_denied" : condition.equals("missing-code") ? "" : "code=ok";
        // when
        var response = get("/auth/google/callback?" + params + "&state=" + state, cookie);
        // then
        assertFailure(response, "login_failed");
        assertThat(TOKEN_CALLS.get()).isEqualTo(0);
    }

    @Test
    @DisplayName("토큰 교환 실패도 U1으로 돌아간다")
    void redirectsAfterTokenFailure() throws Exception {
        // given
        var start = start(null);
        // when
        var response = callback(start, "bad-token", null);
        // then
        assertFailure(response, "login_failed");
        assertThat(TOKEN_CALLS.get()).isEqualTo(1);
    }
}
