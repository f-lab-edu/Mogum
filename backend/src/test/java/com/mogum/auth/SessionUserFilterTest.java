package com.mogum.auth;

import com.mogum.account.domain.User;
import com.mogum.account.domain.UserRole;
import com.mogum.account.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.FindByIndexNameSessionRepository;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionUserFilterTest {
    private final UserRepository users = mock(UserRepository.class);
    private final SessionUserFilter filter = new SessionUserFilter(users,
            (request, response, exception) -> response.setStatus(401));

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void ID로_조회한_회원을_요청_인증으로만_사용한다() throws Exception {
        var user = User.join("google", "sub", "me@example.com", UserRole.ADMIN, Instant.now());
        when(users.findById(17L)).thenReturn(Optional.of(user));
        var request = new MockHttpServletRequest("GET", "/api/me");
        request.getSession().setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, "17");
        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> {
                    var authentication = SecurityContextHolder.getContext().getAuthentication();
                    assertSame(user, authentication.getPrincipal());
                    assertTrue(authentication.isAuthenticated());
                    assertEquals("ROLE_ADMIN", authentication.getAuthorities().iterator().next().getAuthority());
                });
        verify(users).findById(17L);
        assertEquals(1, java.util.Collections.list(request.getSession().getAttributeNames()).size());
    }

    @Test
    void 세션이_없으면_DB_조회나_세션_생성을_하지_않는다() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/me");
        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> assertNull(SecurityContextHolder.getContext().getAuthentication()));
        verifyNoInteractions(users);
        assertNull(request.getSession(false));
    }

    @Test
    void 잘못된_ID_속성은_세션을_무효화한다() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/me");
        request.getSession().setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, "invalid");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail("Invalid session must stop the request"));
        assertEquals(401, response.getStatus());
        assertNull(request.getSession(false));
        verifyNoInteractions(users);
    }
}
