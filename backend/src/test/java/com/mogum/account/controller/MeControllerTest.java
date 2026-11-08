package com.mogum.account.controller;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.auth.SecurityConfiguration;
import com.mogum.auth.AccountPrincipal;
import org.junit.jupiter.api.Test;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import com.mogum.account.domain.UserStatus;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import javax.sql.DataSource;
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

    @ParameterizedTest(name = "회원 상태={0}")
    @EnumSource(UserStatus.class)
    @DisplayName("현재 회원의 상태와 이메일과 이름만 data 안에 반환한다")
    void returnsStatusEmailAndName(UserStatus status) {
        // given
        var user = UserFixtures.withId(UserFixtures.user("google", "sub", "me@example.com"), 7L);
        UserFixtures.withStatus(user, status);
        // 필터를 거치지 않으므로 인자 해석기가 읽는 현재 스레드의 인증에 회원을 넣는다.
        var securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new AccountPrincipal.Member(user.getId(), user.getEmail(), user.getName(), user.getStatus()), null, List.of()));
        SecurityContextHolder.setContext(securityContext);

        // when
        var result = mvc.get().uri("/api/me").exchange();

        // then
        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().isStrictlyEqualTo("""
                {"data":{"status":"%s","email":"me@example.com","name":"me"}}
                """.formatted(status));
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
        assertThat(context.getBeansOfType(EntityManagerFactory.class)).isEmpty();
        assertThat(context.getBeansOfType(UserRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(SecurityConfiguration.class)).isEmpty();
    }
    @Test
    @DisplayName("신청 전 사람은 세션 신원으로 상태와 이메일과 이름만 반환한다")
    void returnsNotAppliedIdentity() {
        // given
        var identity = new AccountPrincipal.NotApplied("google", "sub", "new@example.com", "구글 이름");
        var securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(identity, null, List.of()));
        SecurityContextHolder.setContext(securityContext);

        // when
        var result = mvc.get().uri("/api/me").exchange();

        // then
        assertThat(result).hasStatusOk().bodyJson().isStrictlyEqualTo("""
                {"data":{"status":"NOT_APPLIED","email":"new@example.com","name":"구글 이름"}}
                """);
    }

}
