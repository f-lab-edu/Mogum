package com.mogum.api.auth;

import com.mogum.core.account.ExternalAccount;
import com.mogum.core.account.ExternalIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GoogleAccountMapperTest {
    private final GoogleAccountMapper mapper = new GoogleAccountMapper();

    @Test
    void 서버_등록_ID와_구글_응답을_로그인_입력으로_변환하고_원문을_보존한다() {
        assertEquals(new ExternalAccount(new ExternalIdentity("google", " Case-Sensitive-Sub "), "Admin@Example.com", true),
                mapper.map(authentication("google", attributes())));
    }

    @Test
    void 구글_외_등록은_같은_응답_필드가_있어도_거절한다() {
        assertThrows(OAuth2AuthenticationException.class, () -> mapper.map(authentication("kakao", attributes())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sub", "email"})
    void 식별자와_이메일의_누락_빈값_잘못된_타입을_거절한다(String field) {
        var values = attributes();
        values.remove(field);
        assertThrows(OAuth2AuthenticationException.class, () -> mapper.map(authentication("google", values)));
        for (Object invalid : List.of("", "  ", 123)) {
            values.put(field, invalid);
            assertThrows(OAuth2AuthenticationException.class, () -> mapper.map(authentication("google", values)));
        }
    }

    @Test
    void 이메일_검증_표시가_없거나_boolean_true가_아니면_미인증이다() {
        var values = attributes();
        values.remove("email_verified");
        assertFalse(mapper.map(authentication("google", values)).emailVerified());
        for (Object value : List.of(false, "true", 1)) {
            values.put("email_verified", value);
            assertFalse(mapper.map(authentication("google", values)).emailVerified());
        }
    }

    private static Map<String, Object> attributes() {
        return new HashMap<>(Map.of("name", "test", "sub", " Case-Sensitive-Sub ",
                "email", "Admin@Example.com", "email_verified", true));
    }
    private static OAuth2AuthenticationToken authentication(String registration, Map<String, Object> attributes) {
        return new OAuth2AuthenticationToken(new DefaultOAuth2User(List.of(), attributes, "name"), List.of(), registration);
    }
}
