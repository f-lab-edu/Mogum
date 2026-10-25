package com.mogum.deploy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class BackupFilesTest {
    private static final Path DEPLOY = Path.of("../deploy");
    private static final String KEY = "backups/20261025T040000+0900.dump";

    @ParameterizedTest
    @ValueSource(strings = {"backup.sh", "restore.sh"})
    void 실행_가능한_엄격한_bash이며_비밀_추적과_S3_삭제가_없다(String name) throws Exception {
        var path = DEPLOY.resolve(name);
        assertTrue(Files.isExecutable(path));
        var script = Files.readString(path);
        assertTrue(script.startsWith("#!/usr/bin/env bash\n"));
        assertTrue(script.contains("set -euo pipefail"));
        assertTrue(script.contains("umask 077"));
        assertTrue(script.contains("trap"));
        assertFalse(Pattern.compile("(?m)^\\s*set\\s+[^\\n]*[+-]x").matcher(script).find());
        assertFalse(Pattern.compile("aws\\s+s3\\s+rm|delete-object").matcher(script).find());
        assertFalse(script.contains("docker compose"), "APP_IMAGE 없이도 동작해야 한다");
        var process = new ProcessBuilder("bash", "-n", path.toString()).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output);
    }

    @Test
    void 백업은_custom_덤프를_성공한_뒤_한국_시각_키로_올린다() throws Exception {
        var script = Files.readString(DEPLOY.resolve("backup.sh"));
        assertTrue(script.contains("pg_dump"));
        assertTrue(script.contains("-Fc"));
        assertTrue(script.contains("TZ=Asia/Seoul"));
        assertTrue(script.contains("backups/"));
        assertTrue(script.indexOf("pg_dump") < script.indexOf("aws s3 cp"));
        assertTrue(script.contains("[[ -s $dump ]]"));
        assertTrue(Files.readString(DEPLOY.resolve("mogum.env.example")).contains("MOGUM_BUCKET="));
    }

    @Test
    void 복원_대상은_연습_DB로_고정되고_운영_DB를_대상으로_받지_않는다() throws Exception {
        var script = Files.readString(DEPLOY.resolve("restore.sh"));
        assertTrue(script.contains("mogum_restore_check"));
        assertTrue(script.contains("pg_restore --exit-on-error"));
        assertFalse(Pattern.compile("(?:-d|--dbname[= ])\\s*['\"]?mogum(?:['\"\\s]|$)").matcher(script).find());
        assertFalse(script.contains("POSTGRES_DB"));
        assertTrue(script.contains("--output json"), "latest는 모든 페이지를 합친 뒤 고른다");
    }

    @Test
    void 타이머는_매일_한국_04시이고_놓친_실행을_보충하며_root로_현재_백업을_실행한다() throws Exception {
        var timer = Files.readString(DEPLOY.resolve("systemd/mogum-backup.timer"));
        assertTrue(timer.contains("OnCalendar=*-*-* 19:00:00 UTC"));
        assertTrue(timer.contains("Persistent=true"));
        assertTrue(timer.contains("WantedBy=timers.target"));
        var service = Files.readString(DEPLOY.resolve("systemd/mogum-backup.service"));
        assertTrue(service.contains("Type=oneshot"));
        assertTrue(service.contains("User=root"));
        assertTrue(service.contains("EnvironmentFile=/etc/mogum/mogum.env"));
        assertTrue(service.contains("ExecStart=/opt/mogum/current/deploy/backup.sh"));
        assertTrue(service.contains("After=docker.service network-online.target"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "dump-failure", "empty", "not-ready", "delayed", "ambiguous", "upload-failure"})
    @Timeout(15)
    void 성공한_덤프만_업로드하고_준비_재확인과_임시파일_정리를_한다(String scenario, @TempDir Path root) throws Exception {
        var result = runScript("backup.sh", scenario, root);
        boolean success = List.of("success", "delayed").contains(scenario);
        assertEquals(success ? 0 : 1, result.status(), result.output());
        assertEquals(!List.of("not-ready", "ambiguous").contains(scenario), result.commands().contains("pg_dump"));
        boolean upload = success || scenario.equals("upload-failure");
        assertEquals(upload, result.commands().contains("s3 cp"), result.commands());
        if (upload) {
            assertTrue(result.commands().contains("-Fc"));
            assertTrue(result.commands().contains("s3://test-bucket/" + KEY));
            assertTrue(result.commands().contains("mode=600"));
        }
        if (scenario.equals("not-ready")) {
            assertEquals(24, result.commands().lines().filter(line -> line.equals("sleep 5")).count());
        }
        if (scenario.equals("delayed")) assertTrue(result.commands().contains("sleep 5"));
        if (success) {
            assertTrue(result.output().contains(KEY));
            assertTrue(result.output().contains("bytes"));
            assertTrue(result.output().lines().count() <= 2);
        }
        assertClean(root, result);
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "latest", "no-backup", "download-failure", "restore-failure",
            "create-failure", "report-failure", "cleanup-failure", "invalid-key"})
    @Timeout(15)
    void 복원은_연습_DB만_사용하고_실패해도_DB와_파일을_정리한다(String scenario, @TempDir Path root) throws Exception {
        var result = runScript("restore.sh", scenario, root);
        boolean success = List.of("success", "latest").contains(scenario);
        assertEquals(success ? 0 : 1, result.status(), result.output());
        boolean downloaded = !List.of("no-backup", "invalid-key").contains(scenario);
        assertEquals(downloaded, result.commands().contains("s3 cp"));
        boolean cleanupDb = downloaded && !scenario.equals("download-failure");
        assertEquals(cleanupDb ? 2 : 0,
                result.commands().lines().filter(line -> line.contains("dropdb")).count(), result.commands());
        if (cleanupDb) {
            assertTrue(result.commands().contains("mogum_restore_check"));
            assertFalse(result.commands().contains("POSTGRES_DB"));
            assertFalse(Pattern.compile("(?:-d|--dbname[= ])\\s*mogum(?:\\s|$)").matcher(result.commands()).find());
        }
        if (success) {
            assertTrue(result.output().contains("users | 2"));
            assertTrue(result.commands().contains("pg_tables"));
            assertTrue(result.commands().contains("mode=600"));
        }
        if (scenario.equals("latest")) {
            assertTrue(result.commands().contains("list-objects-v2"));
            assertTrue(result.commands().contains("--prefix backups/"));
            assertTrue(result.commands().contains("s3://test-bucket/" + KEY));
        }
        assertClean(root, result);
    }

    private void assertClean(Path root, Result result) throws Exception {
        try (var files = Files.list(root.resolve("tmp"))) { assertEquals(0, files.count()); }
        assertFalse(result.output().contains("private-dump-content"));
        assertFalse(result.output().contains("private-error-content"));
    }

    private Result runScript(String name, String scenario, Path root) throws Exception {
        var bin = Files.createDirectory(root.resolve("bin"));
        var tmp = Files.createDirectory(root.resolve("tmp"));
        String fake = """
                #!/usr/bin/env bash
                set -euo pipefail
                tool=${0##*/}
                printf '%s %s\\n' "$tool" "$*" >> "$TEST_ROOT/commands"
                case "$tool" in
                  id) printf '0\\n' ;;
                  date) [[ ${TZ:-} == Asia/Seoul ]]; printf '20261025T040000+0900\\n' ;;
                  sleep) exit 0 ;;
                  aws)
                    if [[ $* == *list-objects-v2* ]]; then
                      if [[ $SCENARIO == no-backup ]]; then printf 'null\\n'
                      else printf '\\"backups/20261025T040000+0900.dump\\"\\n'; fi
                    elif [[ $3 == s3://* ]]; then
                      printf private-dump-content > "$4"
                      [[ $SCENARIO != download-failure ]] || exit 1
                    else
                      [[ $(cat "$3") == private-dump-content ]]
                    fi
                    if [[ $* == *'s3 cp'* ]]; then
                      if [[ $3 == s3://* ]]; then path=$4; else path=$3; fi
                      ls -l "$path" | awk '{print "mode=" (substr($1, 1, 10) == "-rw-------" ? "600" : "bad")}' >> "$TEST_ROOT/commands"
                    fi
                    [[ $SCENARIO != upload-failure ]] || exit 1 ;;
                  docker)
                    if [[ $1 == ps ]]; then
                      [[ $* == *com.docker.compose.project=mogum* && $* == *com.docker.compose.service=postgres* ]]
                      printf 'postgres-id\\n'
                      [[ $SCENARIO != ambiguous ]] || printf 'another-id\\n'
                    elif [[ $* == *pg_isready* ]]; then
                      [[ $SCENARIO != not-ready ]] || exit 1
                      if [[ $SCENARIO == delayed && ! -f $TEST_ROOT/ready ]]; then touch "$TEST_ROOT/ready"; exit 1; fi
                    elif [[ $* == *pg_dump* ]]; then
                      [[ $SCENARIO != empty ]] || exit 0
                      printf private-dump-content
                      [[ $SCENARIO != dump-failure ]] || { printf private-error-content >&2; exit 1; }
                    elif [[ $* == *pg_restore* ]]; then
                      [[ $(cat) == private-dump-content ]]
                      [[ $SCENARIO != restore-failure ]] || { printf private-error-content >&2; exit 1; }
                    elif [[ $* == *createdb* ]]; then
                      [[ $SCENARIO != create-failure ]] || exit 1
                    elif [[ $* == *dropdb* ]]; then
                      if [[ $SCENARIO == cleanup-failure && -f $TEST_ROOT/dropped ]]; then exit 1; fi
                      touch "$TEST_ROOT/dropped"
                    elif [[ $* == *psql* ]]; then
                      cat >> "$TEST_ROOT/commands"
                      printf 'users | 2\\n'
                      [[ $SCENARIO != report-failure ]] || exit 1
                    else exit 1; fi ;;
                esac
                """;
        for (var nameOfCommand : List.of("id", "date", "sleep", "aws", "docker")) {
            var path = Files.writeString(bin.resolve(nameOfCommand), fake);
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        }
        var builder = new ProcessBuilder("bash", DEPLOY.resolve(name).toAbsolutePath().toString());
        if (name.equals("restore.sh")) {
            builder.command().add(List.of("latest", "no-backup").contains(scenario) ? "latest"
                    : scenario.equals("invalid-key") ? "releases/not-a-backup" : KEY);
        }
        builder.redirectErrorStream(true);
        builder.environment().remove("APP_IMAGE");
        builder.environment().putAll(Map.of("TEST_ROOT", root.toString(), "SCENARIO", scenario,
                "MOGUM_BUCKET", "test-bucket", "TMPDIR", tmp.toString(),
                "PATH", bin + ":" + System.getenv("PATH")));
        var process = builder.start();
        var output = new String(process.getInputStream().readAllBytes());
        int status = process.waitFor();
        var log = root.resolve("commands");
        return new Result(status, output, Files.exists(log) ? Files.readString(log) : "");
    }

    private record Result(int status, String output, String commands) {}
}
