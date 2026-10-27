package com.mogum.deploy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class DeployFilesTest {
    private static final Path DEPLOY = Path.of("../deploy");
    private static final Pattern VARIABLE = Pattern.compile("(?<!\\$)\\$\\{([A-Z][A-Z0-9_]*)(?::[^}]*)?}");
    private static final Set<String> OPERATING_VARIABLES = Set.of("GOOGLE_CLIENT_ID", "GOOGLE_CLIENT_SECRET",
            "GOOGLE_REDIRECT_URI", "APP_WEB_ORIGIN", "APP_WEB_COOKIE_DOMAIN", "APP_AUTH_ADMIN_EMAILS");

    @Test
    void 운영은_mogum_프로젝트의_세_서비스이고_이미지는_필수_지문_변수다() throws Exception {
        var compose = compose();
        assertEquals("mogum", compose.get("name"));
        assertEquals(Set.of("backend", "postgres", "caddy"), services().keySet());
        assertTrue(service("backend").get("image").toString().matches("\\$\\{APP_IMAGE:\\?[^}]+}"));
        assertEquals("pgvector/pgvector:pg17", service("postgres").get("image"));
        assertEquals("caddy:2", service("caddy").get("image"));
        for (var name : services().keySet()) {
            assertFalse(service(name).containsKey("build"));
            assertEquals("always", service(name).get("restart"));
        }
    }

    @Test
    void 종료_유예와_메모리_상한을_명시하고_DB_준비_후_기동한다() throws Exception {
        assertTrue(seconds(service("backend").get("stop_grace_period")) >= 150);
        long total = 0;
        for (var name : services().keySet()) {
            long memory = mebibytes(service(name).get("mem_limit"));
            assertTrue(memory > 0);
            total += memory;
        }
        assertTrue(total <= 1664);
        assertEquals(1024, mebibytes(service("backend").get("mem_limit")));
        assertEquals(512, mebibytes(service("postgres").get("mem_limit")));
        assertEquals(128, mebibytes(service("caddy").get("mem_limit")));
        var dependency = map(map(service("backend").get("depends_on")).get("postgres"));
        assertEquals("service_healthy", dependency.get("condition"));
        assertNotNull(map(service("postgres").get("healthcheck")).get("test"));
        var environment = map(service("backend").get("environment"));
        assertEquals("aws", environment.get("SPRING_PROFILES_ACTIVE"));
        assertEquals("jdbc:postgresql://postgres:5432/mogum", environment.get("DB_URL"));
    }

    @Test
    void 세_서비스는_journald에_기록하고_Postgres는_오류_상세를_빼고_로그는_한국_시간이다() throws Exception {
        for (var name : services().keySet()) {
            assertEquals(Map.of("driver", "journald"), map(service(name).get("logging")));
        }
        assertEquals(List.of("postgres", "-c", "log_error_verbosity=terse", "-c", "log_timezone=Asia/Seoul"),
                service("postgres").get("command"));
    }

    @Test
    void 저널은_디스크에_보존하고_파일_회전까지_총_14일과_500MiB_이하다() throws Exception {
        var settings = new HashMap<String, String>();
        String section = "";
        for (var raw : Files.readAllLines(DEPLOY.resolve("journald/mogum.conf"))) {
            var line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
            if (line.startsWith("[")) {
                assertEquals("[Journal]", line);
                section = line;
                continue;
            }
            assertEquals("[Journal]", section);
            var parts = line.split("=", 2);
            assertEquals(2, parts.length);
            assertNull(settings.put(parts[0].strip(), parts[1].strip()), "중복 설정: " + parts[0]);
        }
        assertEquals(Set.of("Storage", "SystemMaxUse", "MaxRetentionSec", "MaxFileSec"), settings.keySet());
        assertEquals("persistent", settings.get("Storage"));
        long maximumBytes = journalBytes(settings.get("SystemMaxUse"));
        assertTrue(maximumBytes > 0 && maximumBytes <= 500L * 1024 * 1024);
        long retention = journalSeconds(settings.get("MaxRetentionSec"));
        long fileDuration = journalSeconds(settings.get("MaxFileSec"));
        assertTrue(retention > 0 && fileDuration > 0, "0은 기간 제한을 끈다");
        assertTrue(retention + fileDuration <= 14L * 24 * 60 * 60, "파일 회전 기간도 보관 상한에 포함한다");
    }

    @Test
    void DB는_비공개이고_앱은_루프백_프록시는_TCP_80과_443만_연다() throws Exception {
        assertTrue(ports("postgres").isEmpty());
        for (var port : ports("backend")) {
            assertTrue(port.toString().matches("127\\.0\\.0\\.1:[0-9]+:8080(?:/tcp)?"));
        }
        // 배포 헬스 확인에 쓰는 서버 내부 주소다.
        assertEquals(List.of("127.0.0.1:8080:8080"), ports("backend"));
        assertEquals(Set.of("80:80/tcp", "443:443/tcp"), new HashSet<>(ports("caddy")));
    }

    @Test
    void DB와_인증서는_이름_있는_볼륨이며_프록시_설정은_고정_경로다() throws Exception {
        var volumes = map(compose().get("volumes"));
        assertNamedVolume("postgres", "/var/lib/postgresql/data", volumes);
        assertNamedVolume("caddy", "/data", volumes);
        assertNamedVolume("caddy", "/config", volumes);
        assertTrue(list(service("caddy").get("volumes")).contains("/etc/mogum/caddy:/etc/caddy:ro"));
        for (var name : services().keySet()) {
            for (var volume : list(service(name).getOrDefault("volumes", List.of()))) {
                assertFalse(volume.toString().contains("./"), "릴리스 상대 경로에 의존하면 매번 재생성된다");
                assertFalse(volume.toString().contains("/releases/"));
            }
        }
    }

    @Test
    void 서비스마다_필요한_환경만_받고_비밀은_치환_변수다() throws Exception {
        assertEquals(Set.of("API_DOMAIN"), map(service("caddy").get("environment")).keySet());
        assertEquals(Set.of("POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD"),
                map(service("postgres").get("environment")).keySet());
        var backendKeys = new HashSet<>(OPERATING_VARIABLES);
        backendKeys.addAll(Set.of("SPRING_PROFILES_ACTIVE", "DB_URL", "DB_USERNAME", "DB_PASSWORD"));
        assertEquals(backendKeys, map(service("backend").get("environment")).keySet());
        for (var name : services().keySet()) {
            assertFalse(service(name).containsKey("env_file"), "전체 env 파일을 컨테이너에 넘기지 않는다");
            var environment = map(service(name).get("environment"));
            for (var entry : environment.entrySet()) {
                if (!Set.of("SPRING_PROFILES_ACTIVE", "DB_URL", "POSTGRES_DB").contains(entry.getKey())) {
                    assertTrue(entry.getValue().toString().matches("\\$\\{[A-Z_]+:\\?[^}]+}"));
                }
            }
        }
    }

    @Test
    void env_견본은_운영_변수를_모두_담고_실제_값이_없다() throws Exception {
        var expected = new HashSet<>(OPERATING_VARIABLES);
        var matcher = VARIABLE.matcher(Files.readString(DEPLOY.resolve("compose.yaml")));
        while (matcher.find()) {
            if (!matcher.group(1).equals("APP_IMAGE")) expected.add(matcher.group(1));
        }
        expected.add("API_DOMAIN");
        expected.add("MOGUM_BUCKET");
        var actual = new HashSet<String>();
        for (var line : Files.readAllLines(DEPLOY.resolve("mogum.env.example"))) {
            if (line.isBlank() || line.startsWith("#")) continue;
            var parts = line.split("=", 2);
            assertEquals(2, parts.length);
            assertTrue(actual.add(parts[0]), "중복 변수: " + parts[0]);
            assertTrue(parts[1].isEmpty() || parts[1].contains("{도메인}"));
        }
        assertEquals(expected, actual);
        var example = Files.readString(DEPLOY.resolve("mogum.env.example"));
        assertTrue(example.contains("GOOGLE_REDIRECT_URI=https://api.{도메인}/auth/google/callback"));
        assertTrue(example.contains("APP_WEB_ORIGIN=https://{도메인}"));
        assertTrue(example.contains("APP_WEB_COOKIE_DOMAIN={도메인}"));
        assertEquals("{$API_DOMAIN} {\n    reverse_proxy backend:8080\n}\n",
                Files.readString(DEPLOY.resolve("Caddyfile")));
    }

    @Test
    void 배포_스크립트는_실행_가능한_엄격한_bash이며_비밀을_추적하지_않는다() throws Exception {
        var script = DEPLOY.resolve("deploy.sh");
        assertTrue(Files.isExecutable(script));
        var content = Files.readString(script);
        assertTrue(content.startsWith("#!/usr/bin/env bash\n"));
        assertTrue(content.contains("set -euo pipefail"));
        assertTrue(Pattern.compile("(?m)^for command in [^\\n]*\\btimedatectl\\b[^\\n]*; do$")
                .matcher(content).find(), "필수 명령 검사에 timedatectl을 포함한다");
        assertFalse(Pattern.compile("(?m)^\\s*set\\s+[^\\n]*[+-]x").matcher(content).find());
        var process = new ProcessBuilder("bash", "-n", script.toString()).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output);
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "unchanged-units", "changed-unit", "unchanged-journal", "changed-journal", "unhealthy", "up-failure", "pull-failure", "first-failure",
            "rollback-failure", "link-failure", "invalid-sha", "invalid-reference"})
    @Timeout(20)
    void 배포_실패는_직전으로_복귀하고_성공만_링크와_보존_대상을_바꾼다(String scenario, @TempDir Path root) throws Exception {
        root = root.toRealPath(); // macOS의 /var → /private/var도 서버의 readlink -f와 같은 경로로 비교한다.
        String sha = "a".repeat(40);
        String previousSha = "b".repeat(40);
        String image = "000000000000.dkr.ecr.ap-northeast-2.amazonaws.com/mogum-app@sha256:" + "a".repeat(64);
        String previousImage = image.replace("a".repeat(64), "b".repeat(64));
        var releases = Files.createDirectory(root.resolve("releases"));
        var release = fixtureRelease(releases, sha, image);
        var previous = fixtureRelease(releases, previousSha, previousImage);
        fixtureRelease(releases, "c".repeat(40), previousImage);
        var oldest = fixtureRelease(releases, "d".repeat(40), previousImage);
        var current = root.resolve("current");
        if (!scenario.equals("first-failure")) Files.createSymbolicLink(current, previous);
        if (scenario.equals("invalid-reference")) Files.writeString(release.resolve("app-image.ref"), image + "\nextra\n");
        var envFile = root.resolve("server-env");
        Files.writeString(envFile, "GOOGLE_CLIENT_SECRET=must-never-be-printed\n");

        var systemd = Files.createDirectory(root.resolve("systemd"));
        if (List.of("unchanged-units", "changed-unit", "changed-journal").contains(scenario)) {
            for (var unit : List.of("mogum-backup.service", "mogum-backup.timer")) {
                Files.copy(DEPLOY.resolve("systemd/" + unit), systemd.resolve(unit));
            }
            if (scenario.equals("changed-unit")) Files.writeString(systemd.resolve("mogum-backup.timer"), "# old\n");
        }
        var journalDirectory = root.resolve("journald.conf.d");
        var journalConfig = journalDirectory.resolve("mogum.conf");
        if (List.of("unchanged-units", "changed-unit", "unchanged-journal", "changed-journal").contains(scenario)) {
            Files.createDirectory(journalDirectory);
            Files.copy(DEPLOY.resolve("journald/mogum.conf"), journalConfig);
            if (scenario.equals("changed-journal")) Files.writeString(journalConfig, "# old\n");
        }

        // 실제 서버 경로와 GNU 전용 명령을 임시 경로/명령으로 대체한다. Docker/AWS에는 접속하지 않는다.
        var script = Files.readString(DEPLOY.resolve("deploy.sh"))
                .replace("/opt/mogum/releases", releases.toString())
                .replace("/opt/mogum/current", current.toString())
                .replace("/etc/mogum/mogum.env", envFile.toString())
                .replace("/etc/mogum/caddy", root.resolve("caddy").toString())
                .replace("/etc/systemd/system", systemd.toString())
                .replace("/etc/systemd/journald.conf.d", journalDirectory.toString())
                .replace("health_timeout=180", "health_timeout=1");
        var scriptPath = root.resolve("deploy.sh");
        Files.writeString(scriptPath, script);
        var bin = Files.createDirectory(root.resolve("bin"));
        String fakeCommand = """
                #!/usr/bin/env bash
                set -euo pipefail
                tool=${0##*/}
                case "$tool" in
                  stat) printf '0:600' ;;
                  aws) printf 'test-login-token\n' ;;
                  systemctl|timedatectl) printf '%s %s\\n' "$tool" "$*" >> "$TEST_ROOT/commands" ;;
                  install)
                    printf 'install %s\\n' "$*" >> "$TEST_ROOT/commands"
                    /usr/bin/install "$@" ;;
                  curl)
                    active=$(cat "$TEST_ROOT/active" 2>/dev/null || true)
                    if [[ $SCENARIO == success || $SCENARIO == unchanged-units || $SCENARIO == changed-unit ||
                          $SCENARIO == unchanged-journal || $SCENARIO == changed-journal || $SCENARIO == link-failure ||
                          ( $active == "$PREVIOUS_IMAGE" && $SCENARIO != rollback-failure ) ]]; then
                      printf 200
                    else printf 503; fi ;;
                  sleep) exit 0 ;;
                  mv)
                    [[ $SCENARIO != link-failure ]] || exit 1
                    rm -f "$3"
                    /bin/mv "$2" "$3" ;;
                  find)
                    for directory in "$TEST_ROOT"/releases/*; do
                      timestamp=1
                      [[ ${directory##*/} != d* ]] || timestamp=0
                      [[ -d $directory ]] && printf '%s %s\n' "$timestamp" "${directory##*/}"
                    done ;;
                  docker)
                    printf '%s | %s\n' "${APP_IMAGE:-}" "$*" >> "$TEST_ROOT/commands"
                    case "$1" in
                      login) IFS= read -r token; [[ $token == test-login-token ]] ;;
                      pull) [[ $SCENARIO != pull-failure ]] ;;
                      compose)
                        if [[ " $* " == *' up '* ]]; then
                          printf '%s' "$APP_IMAGE" > "$TEST_ROOT/active"
                          [[ $SCENARIO != up-failure || $APP_IMAGE == "$PREVIOUS_IMAGE" ]]
                        fi ;;
                      image)
                        case "$2" in
                          inspect)
                            if [[ $5 == "$PREVIOUS_IMAGE" ]]; then printf previous-id
                            else printf current-id; fi ;;
                          ls) printf 'current-id\nprevious-id\nunused-id\n' ;;
                        esac ;;
                    esac ;;
                esac
                """;
        for (var command : List.of("stat", "aws", "curl", "sleep", "mv", "find", "docker", "systemctl", "timedatectl", "install")) {
            var path = bin.resolve(command);
            Files.writeString(path, fakeCommand);
            assertTrue(path.toFile().setExecutable(true));
        }
        var builder = new ProcessBuilder("bash", scriptPath.toString(), scenario.equals("invalid-sha") ? "ABC" : sha)
                .redirectErrorStream(true);
        builder.environment().putAll(Map.of("TEST_ROOT", root.toString(), "SCENARIO", scenario,
                "PREVIOUS_IMAGE", previousImage, "PATH", bin + ":" + System.getenv("PATH")));
        var process = builder.start();
        var output = new String(process.getInputStream().readAllBytes());
        boolean success = List.of("success", "unchanged-units", "changed-unit", "unchanged-journal", "changed-journal").contains(scenario);
        assertEquals(success ? 0 : 1, process.waitFor(), output);
        assertFalse(output.contains("must-never-be-printed"));
        assertFalse(output.contains("test-login-token"));
        var commands = Files.exists(root.resolve("commands")) ? Files.readString(root.resolve("commands")) : "";
        assertEquals(success ? List.of("timedatectl set-timezone Asia/Seoul") : List.of(),
                commands.lines().filter(line -> line.startsWith("timedatectl ")).toList(), commands);
        if (success) {
            assertEquals(release, Files.readSymbolicLink(current));
            assertTrue(Files.exists(previous));
            assertFalse(Files.exists(oldest));
            try (var directories = Files.list(releases)) { assertEquals(3, directories.count()); }
            assertTrue(commands.contains("image rm unused-id"));
            assertFalse(commands.contains("image rm previous-id"));
            assertFalse(commands.contains("image rm current-id"));
            assertTrue(output.contains("Deployment healthy"));
            if (List.of("unchanged-units", "changed-journal").contains(scenario)) {
                assertFalse(commands.contains("systemctl daemon-reload"));
                assertFalse(commands.contains("systemctl enable"));
            } else {
                assertTrue(commands.contains("systemctl daemon-reload\nsystemctl enable --now mogum-backup.timer"), commands);
            }
            boolean journalChanged = List.of("success", "changed-journal").contains(scenario);
            assertEquals(journalChanged ? 1 : 0,
                    commands.lines().filter(line -> line.equals("systemctl restart systemd-journald")).count(), commands);
            assertEquals(journalChanged ? 1 : 0,
                    commands.lines().filter(line -> line.startsWith("install -m 644 ")
                            && line.endsWith(" " + journalConfig)).count(), commands);
            assertEquals(journalChanged ? 1 : 0,
                    commands.lines().filter(line -> line.startsWith("install -d ")
                            && line.endsWith(" " + journalDirectory)).count(), commands);
            assertEquals(Files.readString(release.resolve("deploy/journald/mogum.conf")), Files.readString(journalConfig));
            for (var unit : List.of("mogum-backup.service", "mogum-backup.timer")) {
                assertEquals(Files.readString(release.resolve("deploy/systemd/" + unit)),
                        Files.readString(systemd.resolve(unit)));
            }
        } else {
            assertFalse(commands.contains("systemctl"), "헬스 실패나 링크 갱신 실패에서는 설치하지 않는다");
            assertFalse(Files.exists(journalDirectory), "실패 경로에서는 journald 디렉터리도 만들지 않는다");
            assertFalse(commands.lines().anyMatch(line -> line.startsWith("install ") && line.contains(journalDirectory.toString())));
            try (var units = Files.list(systemd)) { assertEquals(0, units.count()); }
            assertTrue(Files.exists(oldest), "실패 시에는 릴리스/이미지를 정리하지 않는다");
            assertFalse(commands.contains("image rm"));
            if (scenario.equals("first-failure")) {
                assertFalse(Files.exists(current));
                assertTrue(output.contains("No previous release"));
            } else {
                assertEquals(previous, Files.readSymbolicLink(current));
                if (scenario.startsWith("invalid-")) {
                    assertFalse(commands.contains("login"));
                    assertFalse(commands.contains(" up "));
                } else {
                    assertTrue(commands.contains(previousImage + " | compose --project-name mogum --env-file "
                            + envFile + " -f " + previous + "/deploy/compose.yaml up -d --no-build --pull never"), output + commands);
                    assertFalse(commands.contains("pull " + previousImage));
                    assertTrue(output.contains(scenario.equals("rollback-failure") ? "Rollback failed" : "Rolled back"));
                    assertEquals(Files.readString(previous.resolve("deploy/Caddyfile")),
                            Files.readString(root.resolve("caddy/Caddyfile")));
                }
            }
        }
    }

    private Path fixtureRelease(Path releases, String sha, String image) throws Exception {
        var directory = Files.createDirectory(releases.resolve(sha));
        var deploy = Files.createDirectory(directory.resolve("deploy"));
        Files.copy(DEPLOY.resolve("compose.yaml"), deploy.resolve("compose.yaml"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(deploy.resolve("Caddyfile"), "# " + sha + "\n" + Files.readString(DEPLOY.resolve("Caddyfile")));
        var systemd = Files.createDirectory(deploy.resolve("systemd"));
        for (var unit : List.of("mogum-backup.service", "mogum-backup.timer")) {
            Files.copy(DEPLOY.resolve("systemd/" + unit), systemd.resolve(unit));
        }
        var journal = Files.createDirectory(deploy.resolve("journald"));
        Files.copy(DEPLOY.resolve("journald/mogum.conf"), journal.resolve("mogum.conf"));
        Files.writeString(directory.resolve("app-image.ref"), image + "\n");
        return directory;
    }

    private void assertNamedVolume(String service, String target, Map<String, Object> volumes) throws Exception {
        var mount = list(service(service).get("volumes")).stream().map(Object::toString)
                .filter(value -> value.endsWith(":" + target)).findFirst().orElseThrow();
        assertTrue(volumes.containsKey(mount.split(":")[0]));
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
        assertTrue(text.matches("[0-9]+s"));
        return Long.parseLong(text.substring(0, text.length() - 1));
    }

    private static long mebibytes(Object value) {
        var text = value.toString().toLowerCase();
        assertTrue(text.matches("[0-9]+m"));
        return Long.parseLong(text.substring(0, text.length() - 1));
    }

    private static long journalSeconds(String value) {
        var matcher = Pattern.compile("([0-9]+)\\s*([a-z]+)?").matcher(value);
        assertTrue(matcher.matches(), "기간 값: " + value);
        long multiplier = switch (matcher.group(2) == null ? "s" : matcher.group(2)) {
            case "d", "day", "days" -> 24 * 60 * 60;
            case "h", "hr", "hour", "hours" -> 60 * 60;
            case "m", "min", "minute", "minutes" -> 60;
            case "s", "sec", "second", "seconds" -> 1;
            default -> throw new AssertionError("지원하지 않는 기간 단위: " + value);
        };
        return Math.multiplyExact(Long.parseLong(matcher.group(1)), multiplier);
    }

    private static long journalBytes(String value) {
        var matcher = Pattern.compile("([0-9]+)\\s*([KMGT]?)").matcher(value);
        assertTrue(matcher.matches(), "크기 값: " + value);
        long multiplier = switch (matcher.group(2)) {
            case "K" -> 1024L;
            case "M" -> 1024L * 1024;
            case "G" -> 1024L * 1024 * 1024;
            case "T" -> 1024L * 1024 * 1024 * 1024;
            default -> 1;
        };
        return Math.multiplyExact(Long.parseLong(matcher.group(1)), multiplier);
    }
}
