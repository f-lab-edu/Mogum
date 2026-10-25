package com.mogum;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AppImageIT {
    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void 빌드된_이미지가_기동하고_비루트_java가_SIGTERM으로_정상_종료한다() throws Exception {
        var appImage = System.getProperty("appImage");
        assertNotNull(appImage, "appImage 시스템 속성으로 검사할 이미지를 지정해야 한다");
        assertFalse(appImage.isBlank(), "appImage는 빈 값일 수 없다");

        try (var network = Network.newNetwork();
             var postgres = new PostgreSQLContainer("pgvector/pgvector:pg17")
                     .withNetwork(network)
                     .withNetworkAliases("postgres")
                     .withStartupTimeout(Duration.ofMinutes(2));
             var app = new GenericContainer<>(DockerImageName.parse(appImage))
                     .withNetwork(network)
                     .withExposedPorts(8080)
                     .withEnv("DB_URL", "jdbc:postgresql://postgres:5432/" + postgres.getDatabaseName())
                     .withEnv("DB_USERNAME", postgres.getUsername())
                     .withEnv("DB_PASSWORD", postgres.getPassword())
                     .withEnv("GOOGLE_CLIENT_ID", System.getProperty("GOOGLE_CLIENT_ID"))
                     .withEnv("GOOGLE_CLIENT_SECRET", System.getProperty("GOOGLE_CLIENT_SECRET"))
                     .waitingFor(Wait.forHttp("/actuator/health").forStatusCode(200))
                     .withStartupTimeout(Duration.ofMinutes(2))) {
            postgres.start();
            app.start();

            try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                var request = HttpRequest.newBuilder(URI.create("http://" + app.getHost() + ":"
                                + app.getMappedPort(8080) + "/actuator/health"))
                        .timeout(Duration.ofSeconds(5)).GET().build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                var mapper = new ObjectMapper();
                assertEquals(mapper.readTree("{\"status\":\"UP\"}"), mapper.readTree(response.body()));
            }

            // exec 사용자가 아니라 SIGTERM을 받는 PID 1의 실제 java/UID를 확인한다.
            var process = app.execInContainer("cat", "/proc/1/comm", "/proc/1/status");
            assertEquals(0, process.getExitCode(), process.getStderr());
            assertTrue(process.getStdout().startsWith("java\n"), process.getStdout());
            var uids = process.getStdout().lines().filter(line -> line.startsWith("Uid:"))
                    .findFirst().orElseThrow().trim().split("\\s+");
            assertEquals(5, uids.length);
            for (int i = 1; i < uids.length; i++) {
                assertTrue(Integer.parseInt(uids[i]) > 0, "PID 1의 UID는 모두 비루트여야 한다");
            }

            // stop()은 컨테이너를 삭제하므로 Docker stop 후 삭제 전에 종료 코드를 읽는다.
            // 진행 중인 작업이 없는 검사에서 15초를 넘으면 SIGKILL(137)로 실패한다.
            app.getDockerClient().stopContainerCmd(app.getContainerId()).withTimeout(15).exec();
            var state = app.getDockerClient().inspectContainerCmd(app.getContainerId()).exec().getState();
            assertEquals(Boolean.FALSE, state.getRunning());
            assertEquals(143L, state.getExitCodeLong(), app.getLogs());
        }
    }
}
