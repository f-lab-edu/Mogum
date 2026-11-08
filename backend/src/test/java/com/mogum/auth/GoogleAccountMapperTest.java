package com.mogum.auth;

import com.mogum.account.service.ExternalAccount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleAccountMapperTest {
    private final GoogleAccountMapper mapper = new GoogleAccountMapper();

    @Test
    @DisplayName("서버 등록 ID와 구글 응답을 로그인 입력으로 변환하고 원문을 보존한다")
    void mapsGoogleAccountWithoutChangingOriginalValues() {
        // given
        var authentication = authentication("google", attributes());

        // when
        var account = mapper.map(authentication);

        // then
        assertThat(account).isEqualTo(new ExternalAccount("google", " Case-Sensitive-Sub ", "Admin@Example.com", true));
    }

    @Test
    @DisplayName("구글 외 등록은 같은 응답 필드가 있어도 거절한다")
    void rejectsOtherProviderRegistration() {
        // given
        var authentication = authentication("kakao", attributes());

        // when & then
        assertThatThrownBy(() -> mapper.map(authentication)).isInstanceOf(OAuth2AuthenticationException.class);
    }

    @ParameterizedTest(name = "필드 {0} 값 [{1}]")
    @MethodSource("invalidIdentityAttributes")
    @DisplayName("식별자와 이메일의 누락과 빈값 및 잘못된 타입을 거절한다")
    void rejectsInvalidIdentityAttribute(String field, Object invalid) {
        // given
        var values = attributes();
        if (invalid == null) values.remove(field);
        else values.put(field, invalid);
        var authentication = authentication("google", values);

        // when & then
        assertThatThrownBy(() -> mapper.map(authentication)).isInstanceOf(OAuth2AuthenticationException.class);
    }

    @ParameterizedTest(name = "인증 표시 [{0}]")
    @MethodSource("unverifiedAttributes")
    @DisplayName("이메일 검증 표시가 없거나 boolean true가 아니면 미인증이다")
    void treatsMissingOrNonBooleanVerificationAsUnverified(Object value) {
        // given
        var values = attributes();
        if (value == null) values.remove("email_verified");
        else values.put("email_verified", value);
        var authentication = authentication("google", values);

        // when
        var account = mapper.map(authentication);

        // then
        assertThat(account.emailVerified()).isFalse();
    }

    private static Stream<Arguments> invalidIdentityAttributes() {
        return Stream.of("sub", "email").flatMap(field -> Stream.of(null, "", "  ", 123)
                .map(value -> Arguments.of(field, value)));
    }

    private static Stream<Arguments> unverifiedAttributes() {
        return Stream.of(null, false, "true", 1).map(value -> Arguments.of(value));
    }

    private static Map<String, Object> attributes() {
        return new HashMap<>(Map.of("name", "test", "sub", " Case-Sensitive-Sub ",
                "email", "Admin@Example.com", "email_verified", true));
    }

    private static OAuth2AuthenticationToken authentication(String registration, Map<String, Object> attributes) {
        return new OAuth2AuthenticationToken(new DefaultOAuth2User(List.of(), attributes, "name"), List.of(), registration);
    }
}
