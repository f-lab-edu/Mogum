package com.mogum;

import org.junit.jupiter.api.DisplayName;
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
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(value = 5, unit = TimeUnit.MINUTES)
class AppImageTest {
    @Test
    @DisplayName("빌드된 서버 이미지가 기동하고 헬스 상태를 응답한다")
    void startsImageWithHealthyServer() throws Exception {
        // given
        try (var running = new RunningApp();
             var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var app = running.app;
            var request = HttpRequest.newBuilder(URI.create("http://" + app.getHost() + ":"
                            + app.getMappedPort(8080) + "/actuator/health"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            var mapper = new ObjectMapper();

            // when
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            // then
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(response.body())).isEqualTo(mapper.readTree("{\"status\":\"UP\"}"));
        }
    }

    @Test
    @DisplayName("SIGTERM을 받는 PID 1의 자바 프로세스는 모든 UID가 비루트다")
    void runsJavaAsNonRootPidOne() throws Exception {
        // given
        try (var running = new RunningApp()) {
            // when — exec 사용자가 아니라 PID 1의 실제 java/UID를 확인한다.
            var process = running.app.execInContainer("cat", "/proc/1/comm", "/proc/1/status");

            // then
            assertThat(process.getExitCode()).as(process.getStderr()).isZero();
            assertThat(process.getStdout()).startsWith("java\n");
            var uids = process.getStdout().lines().filter(line -> line.startsWith("Uid:"))
                    .findFirst().orElseThrow().trim().split("\\s+");
            assertThat(uids).hasSize(5);
            assertThat(Arrays.stream(uids).skip(1).map(Integer::parseInt).toList())
                    .allSatisfy(uid -> assertThat(uid).as("PID 1의 UID").isPositive());
        }
    }

    @Test
    @DisplayName("서버 이미지는 SIGTERM 뒤 제한 시간 안에 종료 코드 143으로 멈춘다")
    void exitsGracefullyOnSigterm() {
        // given
        try (var running = new RunningApp()) {
            var app = running.app;
            // stop()은 컨테이너를 삭제하므로 Docker stop 후 삭제 전에 종료 코드를 읽는다.
            // 진행 중인 작업이 없는 검사에서 15초를 넘으면 SIGKILL(137)로 실패한다.

            // when
            app.getDockerClient().stopContainerCmd(app.getContainerId()).withTimeout(15).exec();

            // then
            var state = app.getDockerClient().inspectContainerCmd(app.getContainerId()).exec().getState();
            assertThat(state.getRunning()).isFalse();
            assertThat(state.getExitCodeLong()).as(app.getLogs()).isEqualTo(143L);
        }
    }

    private static final class RunningApp implements AutoCloseable {
        private final Network network;
        private final PostgreSQLContainer postgres;
        private final GenericContainer<?> app;

        private RunningApp() {
            var appImage = System.getProperty("appImage");
            assertThat(appImage).as("appImage 시스템 속성으로 검사할 이미지를 지정해야 한다").isNotBlank();
            network = Network.newNetwork();
            postgres = new PostgreSQLContainer("pgvector/pgvector:pg17")
                    .withNetwork(network)
                    .withNetworkAliases("postgres")
                    .withStartupTimeout(Duration.ofMinutes(2));
            app = new GenericContainer<>(DockerImageName.parse(appImage))
                    .withNetwork(network)
                    .withExposedPorts(8080)
                    .withEnv("DB_URL", "jdbc:postgresql://postgres:5432/" + postgres.getDatabaseName())
                    .withEnv("DB_USERNAME", postgres.getUsername())
                    .withEnv("DB_PASSWORD", postgres.getPassword())
                    .withEnv("GOOGLE_CLIENT_ID", System.getProperty("GOOGLE_CLIENT_ID"))
                    .withEnv("GOOGLE_CLIENT_SECRET", System.getProperty("GOOGLE_CLIENT_SECRET"))
                    .waitingFor(Wait.forHttp("/actuator/health").forStatusCode(200))
                    .withStartupTimeout(Duration.ofMinutes(2));
            try {
                postgres.start();
                app.start();
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        @Override
        public void close() {
            try (network; postgres; app) {
                // 선언의 역순으로 앱 · DB · 네트워크를 닫는다.
            }
        }
    }
}
