package com.mogum.account.controller;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.auth.SecurityConfiguration;
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
    @DisplayName("현재 회원의 이메일과 상태만 data 안에 반환한다")
    void returnsEmailAndStatus(UserStatus status) {
        // given
        var user = UserFixtures.withId(UserFixtures.user("google", "sub", "me@example.com"), 7L);
        UserFixtures.withStatus(user, status);
        // 필터를 거치지 않으므로 인자 해석기가 읽는 현재 스레드의 인증에 회원을 넣는다.
        var securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
        SecurityContextHolder.setContext(securityContext);

        // when
        var result = mvc.get().uri("/api/me").exchange();

        // then
        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().isStrictlyEqualTo("""
                {"data":{"email":"me@example.com","status":"%s"}}
                """.formatted(status));
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
        assertThat(context.getBeansOfType(EntityManagerFactory.class)).isEmpty();
        assertThat(context.getBeansOfType(UserRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(SecurityConfiguration.class)).isEmpty();
    }
}
