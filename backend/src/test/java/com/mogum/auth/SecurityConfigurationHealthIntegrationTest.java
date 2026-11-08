package com.mogum.auth;

import com.mogum.support.IntegrationTest;
import com.mogum.support.IntegrationTestConfiguration.DatabaseProbe;
import com.mogum.support.TestRequests;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.test.json.JsonCompareMode;

import javax.sql.DataSource;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class SecurityConfigurationHealthIntegrationTest {
    @Autowired private TestRequests requests;
    @Autowired private Environment environment;
    @Autowired private DataSource dataSource;

    @Test
    @DisplayName("로그인 없는 헬스는 세부 정보 없이 UP만 반환한다")
    void returnsOnlyUpWithoutAuthentication() {
        // when
        var response = requests.anonymous().get().uri("/actuator/health").exchange();

        // then
        response.expectStatus().isOk().expectHeader().value("Content-Type", value -> assertThat(value).contains("json"))
                .expectBody().json("{\"status\":\"UP\"}", JsonCompareMode.STRICT);
    }

    @Test
    @DisplayName("DB 연결 실패는 세부 정보 없는 503 DOWN으로 관찰된다")
    void returnsDownWhenDatabaseConnectionFails() {
        // given
        var probe = (DatabaseProbe) dataSource;
        long before = probe.connectionAttempts();
        probe.failConnections(true);
        try {
            // when
            var response = requests.anonymous().get().uri("/actuator/health").exchange();

            // then
            response.expectStatus().isEqualTo(503)
                    .expectBody().json("{\"status\":\"DOWN\"}", JsonCompareMode.STRICT);
            assertThat(probe.connectionAttempts()).isGreaterThan(before);
        } finally {
            probe.failConnections(false);
        }
    }

    @ParameterizedTest(name = "관리 경로 {0}")
    @ValueSource(strings = {"/actuator", "/actuator/info", "/actuator/env"})
    @DisplayName("다른 관리 경로는 설정값 없이 거절한다")
    void rejectsOtherManagementPaths(String path) {
        // when
        var response = requests.anonymous().get().uri(path).exchange();

        // then
        response.expectStatus().isUnauthorized().expectBody().json("""
                {"code":"UNAUTHORIZED","message":"로그인이 필요합니다"}
                """, JsonCompareMode.STRICT);
    }

    @Test
    @DisplayName("헬스의 HEAD 요청은 인증 없이 허용하지 않는다")
    void rejectsAnonymousHeadHealthRequest() {
        // when
        var response = requests.anonymous().head().uri("/actuator/health").exchange();

        // then
        response.expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("정상 종료와 130초 대기가 컨텍스트에 설정된다")
    void configuresGracefulShutdownTimeout() {
        // when
        var timeout = Binder.get(environment).bind("spring.lifecycle.timeout-per-shutdown-phase", Duration.class).get();

        // then
        assertThat(timeout).isEqualTo(Duration.ofSeconds(130));
        assertThat(environment.getProperty("server.shutdown")).isEqualTo("graceful");
    }
}
