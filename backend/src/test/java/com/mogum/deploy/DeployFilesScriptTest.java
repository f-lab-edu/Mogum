package com.mogum.deploy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class DeployFilesScriptTest {
    private static final Path DEPLOY = Path.of("../deploy");

    @Test
    @DisplayName("배포 스크립트는 실행 가능한 엄격한 bash이며 비밀을 추적하지 않는다")
    void usesStrictExecutableDeploymentShell() throws Exception {
        // given
        var script = DEPLOY.resolve("deploy.sh");

        // when
        var content = Files.readString(script);

        // then
        assertThat(Files.isExecutable(script)).isTrue();
        assertThat(content).startsWith("#!/usr/bin/env bash\n");
        assertThat(content).contains("set -euo pipefail");
        assertThat(Pattern.compile("(?m)^for command in [^\\n]*\\btimedatectl\\b[^\\n]*; do$")
                .matcher(content).find()).as("필수 명령 검사에 timedatectl을 포함한다").isTrue();
        assertThat(Pattern.compile("(?m)^\\s*set\\s+[^\\n]*[+-]x").matcher(content).find()).isFalse();
    }

    @Test
    @DisplayName("배포 스크립트는 bash 구문 검사를 통과한다")
    void passesBashSyntaxCheck() throws Exception {
        // given
        var script = DEPLOY.resolve("deploy.sh");

        // when
        var process = new ProcessBuilder("bash", "-n", script.toString()).redirectErrorStream(true).start();

        // then
        var output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as(output).isZero();
    }

    @ParameterizedTest(name = "시나리오 {0}")
    @ValueSource(strings = {"success", "unchanged-units", "changed-unit", "unchanged-journal", "changed-journal", "unhealthy", "up-failure", "pull-failure", "first-failure",
            "rollback-failure", "link-failure", "invalid-sha", "invalid-reference"})
    @Timeout(20)
    @DisplayName("배포 실패는 직전으로 복귀하고 성공만 링크와 보존 대상을 바꾼다")
    void rollsBackFailureAndUpdatesLinksOnlyAfterSuccess(String scenario, @TempDir Path root) throws Exception {
        // given
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
            assertThat(path.toFile().setExecutable(true)).isTrue();
        }
        var builder = new ProcessBuilder("bash", scriptPath.toString(), scenario.equals("invalid-sha") ? "ABC" : sha)
                .redirectErrorStream(true);
        builder.environment().putAll(Map.of("TEST_ROOT", root.toString(), "SCENARIO", scenario,
                "PREVIOUS_IMAGE", previousImage, "PATH", bin + ":" + System.getenv("PATH")));
        // when
        var result = execute(builder);

        // then
        var output = result.output();
        boolean success = List.of("success", "unchanged-units", "changed-unit", "unchanged-journal", "changed-journal").contains(scenario);
        assertThat(result.status()).as(output).isEqualTo(success ? 0 : 1);
        assertThat(output).doesNotContain("must-never-be-printed");
        assertThat(output).doesNotContain("test-login-token");
        var commands = Files.exists(root.resolve("commands")) ? Files.readString(root.resolve("commands")) : "";
        assertThat(commands.lines().filter(line -> line.startsWith("timedatectl ")).toList())
                .as(commands).isEqualTo(success ? List.of("timedatectl set-timezone Asia/Seoul") : List.of());
        if (success) {
            assertThat(Files.readSymbolicLink(current)).isEqualTo(release);
            assertThat(Files.exists(previous)).isTrue();
            assertThat(Files.exists(oldest)).isFalse();
            try (var directories = Files.list(releases)) { assertThat(directories.count()).isEqualTo(3); }
            assertThat(commands).contains("image rm unused-id");
            assertThat(commands).doesNotContain("image rm previous-id");
            assertThat(commands).doesNotContain("image rm current-id");
            assertThat(output).contains("Deployment healthy");
            if (List.of("unchanged-units", "changed-journal").contains(scenario)) {
                assertThat(commands).doesNotContain("systemctl daemon-reload");
                assertThat(commands).doesNotContain("systemctl enable");
            } else {
                assertThat(commands)
                        .as(commands).contains("systemctl daemon-reload\nsystemctl enable --now mogum-backup.timer");
            }
            boolean journalChanged = List.of("success", "changed-journal").contains(scenario);
            assertThat(commands.lines().filter(line -> line.equals("systemctl restart systemd-journald")).count())
                    .as(commands).isEqualTo(journalChanged ? 1 : 0);
            assertThat(commands.lines().filter(line -> line.startsWith("install -m 644 ")
                            && line.endsWith(" " + journalConfig)).count()).as(commands).isEqualTo(journalChanged ? 1 : 0);
            assertThat(commands.lines().filter(line -> line.startsWith("install -d ")
                            && line.endsWith(" " + journalDirectory)).count()).as(commands).isEqualTo(journalChanged ? 1 : 0);
            assertThat(Files.readString(journalConfig))
                    .isEqualTo(Files.readString(release.resolve("deploy/journald/mogum.conf")));
            for (var unit : List.of("mogum-backup.service", "mogum-backup.timer")) {
                assertThat(Files.readString(systemd.resolve(unit)))
                        .isEqualTo(Files.readString(release.resolve("deploy/systemd/" + unit)));
            }
        } else {
            assertThat(commands).as("헬스 실패나 링크 갱신 실패에서는 설치하지 않는다").doesNotContain("systemctl");
            assertThat(Files.exists(journalDirectory)).as("실패 경로에서는 journald 디렉터리도 만들지 않는다").isFalse();
            assertThat(commands.lines().anyMatch(line -> line.startsWith("install ")
                    && line.contains(journalDirectory.toString()))).isFalse();
            try (var units = Files.list(systemd)) { assertThat(units.count()).isEqualTo(0); }
            assertThat(Files.exists(oldest)).as("실패 시에는 릴리스/이미지를 정리하지 않는다").isTrue();
            assertThat(commands).doesNotContain("image rm");
            if (scenario.equals("first-failure")) {
                assertThat(Files.exists(current)).isFalse();
                assertThat(output).contains("No previous release");
            } else {
                assertThat(Files.readSymbolicLink(current)).isEqualTo(previous);
                if (scenario.startsWith("invalid-")) {
                    assertThat(commands).doesNotContain("login");
                    assertThat(commands).doesNotContain(" up ");
                } else {
                    assertThat(commands)
                            .as(output + commands).contains(previousImage + " | compose --project-name mogum --env-file "
                            + envFile + " -f " + previous + "/deploy/compose.yaml up -d --no-build --pull never");
                    assertThat(commands).doesNotContain("pull " + previousImage);
                    assertThat(output).contains(scenario.equals("rollback-failure") ? "Rollback failed" : "Rolled back");
                    assertThat(Files.readString(root.resolve("caddy/Caddyfile")))
                            .isEqualTo(Files.readString(previous.resolve("deploy/Caddyfile")));
                }
            }
        }
    }

    private Result execute(ProcessBuilder builder) throws Exception {
        var process = builder.start();
        var output = new String(process.getInputStream().readAllBytes());
        return new Result(process.waitFor(), output);
    }

    private record Result(int status, String output) { }

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
}
