package com.mogum.deploy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class DeployFilesTest {
    private static final Path DEPLOY = Path.of("../deploy");
    private static final Pattern VARIABLE = Pattern.compile("(?<!\\$)\\$\\{([A-Z][A-Z0-9_]*)(?::[^}]*)?}");
    private static final Set<String> OPERATING_VARIABLES = Set.of("GOOGLE_CLIENT_ID", "GOOGLE_CLIENT_SECRET",
            "GOOGLE_REDIRECT_URI", "APP_WEB_ORIGIN", "APP_WEB_COOKIE_DOMAIN", "APP_AUTH_ADMIN_EMAILS");

    @Test
    @DisplayName("운영은 mogum 프로젝트의 세 서비스이고 이미지는 필수 지문 변수다")
    void definesThreeServicesAndRequiredImageDigest() throws Exception {
        // when
        var compose = compose();

        // then
        assertThat(compose.get("name")).isEqualTo("mogum");
        assertThat(services().keySet()).isEqualTo(Set.of("backend", "postgres", "caddy"));
        assertThat(service("backend").get("image").toString()).matches("\\$\\{APP_IMAGE:\\?[^}]+}");
        assertThat(service("postgres").get("image")).isEqualTo("pgvector/pgvector:pg17");
        assertThat(service("caddy").get("image")).isEqualTo("caddy:2");
        for (var name : services().keySet()) {
            assertThat(service(name)).doesNotContainKey("build");
            assertThat(service(name).get("restart")).isEqualTo("always");
        }
    }

    @Test
    @DisplayName("종료 유예와 메모리 상한을 명시하고 DB 준비 후 기동한다")
    void waitsForDatabaseWithinShutdownAndMemoryLimits() throws Exception {
        // when
        var gracePeriod = seconds(service("backend").get("stop_grace_period"));

        // then
        assertThat(gracePeriod).isGreaterThanOrEqualTo(150);
        long total = 0;
        for (var name : services().keySet()) {
            long memory = mebibytes(service(name).get("mem_limit"));
            assertThat(memory).isPositive();
            total += memory;
        }
        assertThat(total).isLessThanOrEqualTo(1664);
        assertThat(mebibytes(service("backend").get("mem_limit"))).isEqualTo(1024);
        assertThat(mebibytes(service("postgres").get("mem_limit"))).isEqualTo(512);
        assertThat(mebibytes(service("caddy").get("mem_limit"))).isEqualTo(128);
        var dependency = map(map(service("backend").get("depends_on")).get("postgres"));
        assertThat(dependency.get("condition")).isEqualTo("service_healthy");
        assertThat(map(service("postgres").get("healthcheck")).get("test")).isNotNull();
        var environment = map(service("backend").get("environment"));
        assertThat(environment.get("SPRING_PROFILES_ACTIVE")).isEqualTo("aws");
        assertThat(environment.get("DB_URL")).isEqualTo("jdbc:postgresql://postgres:5432/mogum");
    }

    @Test
    @DisplayName("세 서비스는 journald에 기록하고 Postgres는 오류 상세를 빼고 로그는 한국 시간이다")
    void configuresJournaldAndSeoulPostgresLogs() throws Exception {
        // when
        var definitions = services();

        // then
        for (var name : definitions.keySet()) {
            assertThat(map(service(name).get("logging"))).isEqualTo(Map.of("driver", "journald"));
        }
        assertThat(service("postgres").get("command"))
                .isEqualTo(List.of("postgres", "-c", "log_error_verbosity=terse", "-c", "log_timezone=Asia/Seoul"));
    }

    @Test
    @DisplayName("DB는 비공개이고 앱은 루프백 프록시는 TCP 80과 443만 연다")
    void exposesOnlyLoopbackBackendAndHttpProxy() throws Exception {
        // when
        var databasePorts = ports("postgres");

        // then
        assertThat(databasePorts).isEmpty();
        for (var port : ports("backend")) {
            assertThat(port.toString()).matches("127\\.0\\.0\\.1:[0-9]+:8080(?:/tcp)?");
        }
        // 배포 헬스 확인에 쓰는 서버 내부 주소다.
        assertThat(ports("backend")).isEqualTo(List.of("127.0.0.1:8080:8080"));
        assertThat(new HashSet<>(ports("caddy"))).isEqualTo(Set.of("80:80/tcp", "443:443/tcp"));
    }

    @Test
    @DisplayName("DB와 인증서는 이름 있는 볼륨이며 프록시 설정은 고정 경로다")
    void usesNamedVolumesAndStableProxyPaths() throws Exception {
        // when
        var volumes = map(compose().get("volumes"));

        // then
        assertNamedVolume("postgres", "/var/lib/postgresql/data", volumes);
        assertNamedVolume("caddy", "/data", volumes);
        assertNamedVolume("caddy", "/config", volumes);
        assertThat(list(service("caddy").get("volumes")).contains("/etc/mogum/caddy:/etc/caddy:ro")).isTrue();
        for (var name : services().keySet()) {
            for (var volume : list(service(name).getOrDefault("volumes", List.of()))) {
                assertThat(volume.toString()).as("릴리스 상대 경로에 의존하면 매번 재생성된다").doesNotContain("./");
                assertThat(volume.toString()).doesNotContain("/releases/");
            }
        }
    }

    @Test
    @DisplayName("서비스마다 필요한 환경만 받고 비밀은 치환 변수다")
    void passesOnlyRequiredEnvironmentVariables() throws Exception {
        // when
        var proxyEnvironment = map(service("caddy").get("environment"));

        // then
        assertThat(proxyEnvironment.keySet()).isEqualTo(Set.of("API_DOMAIN"));
        assertThat(map(service("postgres").get("environment")).keySet())
                .isEqualTo(Set.of("POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD"));
        var backendKeys = new HashSet<>(OPERATING_VARIABLES);
        backendKeys.addAll(Set.of("SPRING_PROFILES_ACTIVE", "DB_URL", "DB_USERNAME", "DB_PASSWORD"));
        assertThat(map(service("backend").get("environment")).keySet()).isEqualTo(backendKeys);
        for (var name : services().keySet()) {
            assertThat(service(name)).as("전체 env 파일을 컨테이너에 넘기지 않는다").doesNotContainKey("env_file");
            var environment = map(service(name).get("environment"));
            for (var entry : environment.entrySet()) {
                if (!Set.of("SPRING_PROFILES_ACTIVE", "DB_URL", "POSTGRES_DB").contains(entry.getKey())) {
                    assertThat(entry.getValue().toString()).matches("\\$\\{[A-Z_]+:\\?[^}]+}");
                }
            }
        }
    }

    @Test
    @DisplayName("env 견본은 운영 변수를 모두 담고 실제 값이 없다")
    void documentsAllVariablesWithoutRealValues() throws Exception {
        // given
        var expected = new HashSet<>(OPERATING_VARIABLES);
        var matcher = VARIABLE.matcher(Files.readString(DEPLOY.resolve("compose.yaml")));
        while (matcher.find()) {
            if (!matcher.group(1).equals("APP_IMAGE")) expected.add(matcher.group(1));
        }
        expected.add("API_DOMAIN");
        expected.add("MOGUM_BUCKET");
        var actual = new HashSet<String>();

        // when
        var lines = Files.readAllLines(DEPLOY.resolve("mogum.env.example"));

        // then
        for (var line : lines) {
            if (line.isBlank() || line.startsWith("#")) continue;
            var parts = line.split("=", 2);
            assertThat(parts.length).isEqualTo(2);
            assertThat(actual.add(parts[0])).as("중복 변수: " + parts[0]).isTrue();
            assertThat(parts[1].isEmpty() || parts[1].contains("{도메인}")).isTrue();
        }
        assertThat(actual).isEqualTo(expected);
        var example = Files.readString(DEPLOY.resolve("mogum.env.example"));
        assertThat(example).contains("GOOGLE_REDIRECT_URI=https://api.{도메인}/auth/google/callback");
        assertThat(example).contains("APP_WEB_ORIGIN=https://{도메인}");
        assertThat(example).contains("APP_WEB_COOKIE_DOMAIN={도메인}");
    }

    @Test
    @DisplayName("프록시는 설정한 API 도메인을 서버의 8080 포트로 전달한다")
    void proxiesConfiguredDomainToBackend() throws Exception {
        // when
        var caddy = Files.readString(DEPLOY.resolve("Caddyfile"));

        // then
        assertThat(caddy).isEqualTo("{$API_DOMAIN} {\n    reverse_proxy backend:8080\n}\n");
    }

    private void assertNamedVolume(String service, String target, Map<String, Object> volumes) throws Exception {
        var mount = list(service(service).get("volumes")).stream().map(Object::toString)
                .filter(value -> value.endsWith(":" + target)).findFirst().orElseThrow();
        assertThat(volumes).containsKey(mount.split(":")[0]);
    }

    private Map<String, Object> compose() throws Exception {
        try (var input = Files.newInputStream(DEPLOY.resolve("compose.yaml"))) {
            return new Yaml().load(input);
        }
    }

    private Map<String, Object> services() throws Exception { return map(compose().get("services")); }
    private Map<String, Object> service(String name) throws Exception { return map(services().get(name)); }
    private List<?> ports(String name) throws Exception { return list(service(name).getOrDefault("ports", List.of())); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    private static List<?> list(Object value) { return (List<?>) value; }

    private static long seconds(Object value) {
        var text = value.toString();
        assertThat(text).matches("[0-9]+s");
        return Long.parseLong(text.substring(0, text.length() - 1));
    }

    private static long mebibytes(Object value) {
        var text = value.toString().toLowerCase();
        assertThat(text).matches("[0-9]+m");
        return Long.parseLong(text.substring(0, text.length() - 1));
    }
}
