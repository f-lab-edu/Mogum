package com.mogum.auth;

import com.mogum.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityConfigurationHealthIT extends PostgresTestConfiguration {
    @Autowired private ObjectMapper mapper;
    @Autowired private Environment environment;
    @MockitoSpyBean private DataSource dataSource;
    @Value("${local.server.port}") private int port;

    @Test
    void 로그인_없이_헬스는_세부_정보_없이_UP만_반환한다() throws Exception {
        var response = http("GET", "/actuator/health");

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().contains("json"));
        assertEquals(mapper.readTree("{\"status\":\"UP\"}"), mapper.readTree(response.body()));
    }

    @Test
    void DB_연결이_실패하면_헬스는_세부_정보_없이_503_DOWN이다() throws Exception {
        doThrow(new SQLException("test database unavailable")).when(dataSource).getConnection();
        try {
            var response = http("GET", "/actuator/health");

            assertEquals(503, response.statusCode());
            assertEquals(mapper.readTree("{\"status\":\"DOWN\"}"), mapper.readTree(response.body()));
        } finally {
            reset(dataSource);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator", "/actuator/info", "/actuator/env"})
    void 다른_관리_경로는_설정값_없이_거절한다(String path) throws Exception {
        var response = http("GET", path);

        assertEquals(401, response.statusCode());
        assertEquals(mapper.readTree("""
                {"code":"UNAUTHORIZED","message":"로그인이 필요합니다"}
                """), mapper.readTree(response.body()));
    }

    @Test
    void 헬스의_HEAD_요청은_인증_없이_허용하지_않는다() throws Exception {
        assertEquals(401, http("HEAD", "/actuator/health").statusCode());
    }

    @Test
    void 정상_종료와_130초_대기가_컨텍스트에_설정된다() {
        assertEquals("graceful", environment.getProperty("server.shutdown"));
        assertEquals(Duration.ofSeconds(130), Binder.get(environment)
                .bind("spring.lifecycle.timeout-per-shutdown-phase", Duration.class).get());
    }

    private HttpResponse<String> http(String method, String path) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(10))
                    .method(method, HttpRequest.BodyPublishers.noBody()).build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
