package com.mogum.auth;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.FakeUserRepository;
import com.mogum.account.repository.UserRepository;
import com.mogum.common.logging.RequestLogFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.FindByIndexNameSessionRepository;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class SessionUserFilterTest {
    @Mock private UserRepository untouchedUsers;

    // 다른 테스트가 같은 스레드에 남긴 요청 상태도 제거한다.
    @BeforeEach
    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    @DisplayName("ID로 읽은 회원은 요청 인증에만 넣고 세션에는 ID만 남긴다")
    void authenticatesRequestWithoutAddingUserToSession() throws Exception {
        // given
        var users = new FakeUserRepository();
        var user = users.save(UserFixtures.withId(
                UserFixtures.user("google", "sub", "me@example.com"), 17L));
        var filter = filter(users);
        var request = new MockHttpServletRequest("GET", "/api/me");
        request.getSession().setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, "17");
        var chainCalled = new AtomicBoolean();

        // when
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            chainCalled.set(true);
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            assertThat(authentication.getPrincipal()).isSameAs(user);
            assertThat(authentication.isAuthenticated()).isTrue();
            assertThat(authentication.getAuthorities()).isEmpty();
            assertThat(MDC.get(RequestLogFilter.USER_ID)).isEqualTo("17");
        });

        // then
        assertThat(chainCalled).isTrue();
        assertThat(Collections.list(request.getSession().getAttributeNames()))
                .containsExactly(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME);
        assertThat(request.getSession().getAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME))
                .isEqualTo("17");
        // MDC 정리는 바깥 요청 필터의 책임이다.
        assertThat(MDC.get(RequestLogFilter.USER_ID)).isEqualTo("17");
    }

    @Test
    @DisplayName("세션이 없으면 DB를 조회하거나 세션을 만들지 않고 다음 필터로 진행한다")
    void skipsDatabaseAndSessionCreationWithoutSession() throws Exception {
        // given
        var filter = filter(untouchedUsers);
        var request = new MockHttpServletRequest("GET", "/api/me");
        var chainCalled = new AtomicBoolean();

        // when
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            chainCalled.set(true);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        });

        // then
        assertThat(chainCalled).isTrue();
        then(untouchedUsers).shouldHaveNoInteractions();
        assertThat(request.getSession(false)).isNull();
        assertThat(MDC.get(RequestLogFilter.USER_ID)).isNull();
    }

    @Test
    @DisplayName("잘못된 ID는 DB 조회 없이 세션을 무효화하고 요청을 401로 중단한다")
    void invalidatesMalformedSessionWithoutDatabaseAccess() throws Exception {
        // given
        var filter = filter(untouchedUsers);
        var request = new MockHttpServletRequest("GET", "/api/me");
        request.getSession().setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, "invalid");
        var response = new MockHttpServletResponse();
        var chainCalled = new AtomicBoolean();

        // when
        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        // then
        assertThat(chainCalled).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(request.getSession(false)).isNull();
        then(untouchedUsers).shouldHaveNoInteractions();
        assertThat(MDC.get(RequestLogFilter.USER_ID)).isNull();
    }

    @Test
    @DisplayName("세션의 회원이 없으면 요청을 401로 중단하고 MDC에 회원 ID를 넣지 않는다")
    void rejectsMissingSessionUserWithoutSettingMdc() throws Exception {
        // given
        var filter = filter(new FakeUserRepository());
        var request = new MockHttpServletRequest("GET", "/api/me");
        request.getSession().setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, "18");
        var response = new MockHttpServletResponse();
        var chainCalled = new AtomicBoolean();

        // when
        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        // then
        assertThat(chainCalled).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(request.getSession(false)).isNull();
        assertThat(MDC.get(RequestLogFilter.USER_ID)).isNull();
    }

    private static SessionUserFilter filter(UserRepository users) {
        return new SessionUserFilter(users, (request, response, exception) -> response.setStatus(401));
    }
}
