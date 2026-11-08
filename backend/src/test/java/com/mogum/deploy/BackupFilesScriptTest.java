package com.mogum.deploy;

import org.junit.jupiter.api.DisplayName;
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

import static org.assertj.core.api.Assertions.assertThat;

class BackupFilesScriptTest {
    private static final Path DEPLOY = Path.of("../deploy");
    private static final String KEY = "backups/20261025T040000+0900.dump";

    @ParameterizedTest(name = "시나리오 {0}")
    @ValueSource(strings = {"success", "dump-failure", "empty", "not-ready", "delayed", "ambiguous", "upload-failure"})
    @Timeout(15)
    @DisplayName("성공한 덤프만 업로드하고 준비 재확인과 임시파일 정리를 한다")
    void uploadsOnlySuccessfulDumpsAndCleansTemporaryFiles(String scenario, @TempDir Path root) throws Exception {
        // when
        var result = runScript("backup.sh", scenario, root);

        // then
        boolean success = List.of("success", "delayed").contains(scenario);
        assertThat(result.status()).as(result.output()).isEqualTo(success ? 0 : 1);
        assertThat(result.commands().contains("pg_dump")).isEqualTo(!List.of("not-ready", "ambiguous").contains(scenario));
        boolean upload = success || scenario.equals("upload-failure");
        assertThat(result.commands().contains("s3 cp")).as(result.commands()).isEqualTo(upload);
        if (upload) {
            assertThat(result.commands()).contains("-Fc");
            assertThat(result.commands()).contains("s3://test-bucket/" + KEY);
            assertThat(result.commands()).contains("mode=600");
        }
        if (scenario.equals("not-ready")) {
            assertThat(result.commands().lines().filter(line -> line.equals("sleep 5")).count()).isEqualTo(24);
        }
        if (scenario.equals("delayed")) assertThat(result.commands()).contains("sleep 5");
        if (success) {
            assertThat(result.output()).contains(KEY);
            assertThat(result.output()).contains("bytes");
            assertThat(result.output().lines().count() <= 2).isTrue();
        }
        assertClean(root, result);
    }

    @ParameterizedTest(name = "시나리오 {0}")
    @ValueSource(strings = {"success", "latest", "no-backup", "download-failure", "restore-failure",
            "create-failure", "report-failure", "cleanup-failure", "invalid-key"})
    @Timeout(15)
    @DisplayName("복원은 연습 DB만 사용하고 실패해도 DB와 파일을 정리한다")
    void restoresPracticeDatabaseAndCleansAfterFailures(String scenario, @TempDir Path root) throws Exception {
        // when
        var result = runScript("restore.sh", scenario, root);

        // then
        boolean success = List.of("success", "latest").contains(scenario);
        assertThat(result.status()).as(result.output()).isEqualTo(success ? 0 : 1);
        boolean downloaded = !List.of("no-backup", "invalid-key").contains(scenario);
        assertThat(result.commands().contains("s3 cp")).isEqualTo(downloaded);
        boolean cleanupDb = downloaded && !scenario.equals("download-failure");
        assertThat(result.commands().lines().filter(line -> line.contains("dropdb")).count())
                .as(result.commands()).isEqualTo(cleanupDb ? 2 : 0);
        if (cleanupDb) {
            assertThat(result.commands()).contains("mogum_restore_check");
            assertThat(result.commands()).doesNotContain("POSTGRES_DB");
            assertThat(Pattern.compile("(?:-d|--dbname[= ])\\s*mogum(?:\\s|$)").matcher(result.commands()).find()).isFalse();
        }
        if (success) {
            assertThat(result.output()).contains("users | 2");
            assertThat(result.commands()).contains("pg_tables");
            assertThat(result.commands()).contains("mode=600");
        }
        if (scenario.equals("latest")) {
            assertThat(result.commands()).contains("list-objects-v2");
            assertThat(result.commands()).contains("--prefix backups/");
            assertThat(result.commands()).contains("s3://test-bucket/" + KEY);
        }
        assertClean(root, result);
    }

    private void assertClean(Path root, Result result) throws Exception {
        try (var files = Files.list(root.resolve("tmp"))) { assertThat(files.count()).isEqualTo(0); }
        assertThat(result.output()).doesNotContain("private-dump-content");
        assertThat(result.output()).doesNotContain("private-error-content");
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
