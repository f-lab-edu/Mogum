package com.mogum.support;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class TestRequestsIntegrationTest {
    @Autowired private TestRequests requests;
    @Autowired private UserRepository users;
    @Autowired private JdbcIndexedSessionRepository sessions;

    @Test
    @DisplayName("회원 ID만 담은 세션 쿠키로 내 정보에 접근한다")
    void authenticatesUsingUserIdSession() {
        // given
        var user = users.save(UserFixtures.user("http@example.com"));
        var client = requests.asUser(user.getId());

        // when
        var response = client.get().uri("/api/me").exchange().returnResult(String.class);

        // then
        assertThat(response.getStatus().value()).isEqualTo(200);
        assertThat(response.getResponseBody()).contains("http@example.com");
        var stored = sessions.findByPrincipalName(Long.toString(user.getId()));
        assertThat(stored).hasSize(1);
        Session session = stored.values().iterator().next();
        assertThat(session.getAttributeNames())
                .containsExactly(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME);
    }

    @Test
    @DisplayName("도우미가 받은 CSRF 쿠키와 헤더로 로그아웃 요청이 성공한다")
    void sendsCsrfTokenForStateChangingRequest() {
        // given
        var user = users.save(UserFixtures.user("logout@example.com"));
        var client = requests.asUser(user.getId());

        // when
        var response = client.post().uri("/api/auth/logout").exchange().returnResult(String.class);

        // then
        assertThat(response.getStatus().value()).isEqualTo(204);
        assertThat(sessions.findByPrincipalName(Long.toString(user.getId()))).isEmpty();
    }
}
