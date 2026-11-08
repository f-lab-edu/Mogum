package com.mogum.account.controller;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.domain.UserRole;
import com.mogum.account.repository.UserRepository;
import com.mogum.auth.SecurityConfiguration;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@WebMvcTest(MeController.class)
@AutoConfigureMockMvc(addFilters = false)
class MeControllerTest {
    @Autowired private MockMvcTester mvc;
    @Autowired private ApplicationContext context;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest(name = "약관 동의 여부={0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("현재 회원의 공개 필드와 약관 동의 여부를 data 안에 반환한다")
    void returnsPublicFieldsAndTermsAgreement(boolean termsAgreed) {
        // given
        var user = UserFixtures.withId(UserFixtures.user("google", "sub", "me@example.com", UserRole.ADMIN), 7L);
        if (termsAgreed) {
            UserFixtures.withTermsAgreedAt(user, Instant.parse("2026-11-08T00:00:00Z"));
        }
        // 필터를 거치지 않으므로 인자 해석기가 읽는 현재 스레드의 인증에 회원을 넣는다.
        var securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
        SecurityContextHolder.setContext(securityContext);

        // when
        var result = mvc.get().uri("/api/me").exchange();

        // then
        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().isStrictlyEqualTo("""
                {"data":{"email":"me@example.com","status":"ACTIVE","role":"ADMIN",
                 "termsAgreed":%s,"waitlistPosition":null}}
                """.formatted(termsAgreed));
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
        assertThat(context.getBeansOfType(EntityManagerFactory.class)).isEmpty();
        assertThat(context.getBeansOfType(UserRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(SecurityConfiguration.class)).isEmpty();
    }
}
