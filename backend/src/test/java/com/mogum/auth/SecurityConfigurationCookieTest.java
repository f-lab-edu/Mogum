package com.mogum.auth;

import com.mogum.config.WebProperties;
import com.mogum.support.IntegrationTestConfiguration.CookieUnitSupport.SessionCookieConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.web.http.CookieSerializer;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigurationCookieTest {
    @ParameterizedTest(name = "설정 도메인 [{0}]")
    @ValueSource(strings = {"", "mogum.example"})
    @DisplayName("로컬과 등록 도메인 설정 모두 세션 쿠키에는 Domain 없이 보안 속성을 강제한다")
    void keepsSessionCookieHostOnlyForEitherDomainSetting(String domain) {
        // given
        var runner = new ApplicationContextRunner().withUserConfiguration(SessionCookieConfiguration.class)
                .withPropertyValues("app.web.cookie-domain=" + domain);
        // when
        runner.run(context -> {
            // then
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(WebProperties.class).cookieDomain()).isEqualTo(domain);
            var request = new MockHttpServletRequest();
            request.setServerName("api.mogum.example");
            request.setSecure(!domain.isEmpty());
            var response = new MockHttpServletResponse();
            context.getBean(CookieSerializer.class).writeCookieValue(
                    new CookieSerializer.CookieValue(request, response, "test-session"));
            var cookie = response.getCookie("SESSION");
            assertThat(cookie).isNotNull();
            assertThat(cookie.getDomain()).isNull();
            assertThat(cookie.isHttpOnly()).isTrue();
            assertThat(cookie.getSecure()).isTrue();
            assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
            assertThat(cookie.getPath()).isEqualTo("/");
        });
    }
}
