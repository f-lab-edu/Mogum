package com.mogum.deploy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class BackupFilesTest {
    private static final Path DEPLOY = Path.of("../deploy");

    @ParameterizedTest(name = "파일 {0}")
    @ValueSource(strings = {"backup.sh", "restore.sh"})
    @DisplayName("실행 가능한 엄격한 bash이며 비밀 추적과 S3 삭제가 없다")
    void usesStrictExecutableShellWithoutSecretsOrDeletion(String name) throws Exception {
        // given
        var path = DEPLOY.resolve(name);

        // when
        var script = Files.readString(path);

        // then
        assertThat(Files.isExecutable(path)).isTrue();
        assertThat(script).startsWith("#!/usr/bin/env bash\n");
        assertThat(script).contains("set -euo pipefail");
        assertThat(script).contains("umask 077");
        assertThat(script).contains("trap");
        assertThat(Pattern.compile("(?m)^\\s*set\\s+[^\\n]*[+-]x").matcher(script).find()).isFalse();
        assertThat(Pattern.compile("aws\\s+s3\\s+rm|delete-object").matcher(script).find()).isFalse();
        assertThat(script).as("APP_IMAGE 없이도 동작해야 한다").doesNotContain("docker compose");
    }

    @Test
    @DisplayName("백업은 custom 덤프를 성공한 뒤 한국 시각 키로 올린다")
    void uploadsSuccessfulDumpWithSeoulTimestamp() throws Exception {
        // when
        var script = Files.readString(DEPLOY.resolve("backup.sh"));

        // then
        assertThat(script).contains("pg_dump");
        assertThat(script).contains("-Fc");
        assertThat(script).contains("TZ=Asia/Seoul");
        assertThat(script).contains("backups/");
        assertThat(script.indexOf("pg_dump") < script.indexOf("aws s3 cp")).isTrue();
        assertThat(script).contains("[[ -s $dump ]]");
        assertThat(Files.readString(DEPLOY.resolve("mogum.env.example"))).contains("MOGUM_BUCKET=");
    }

    @Test
    @DisplayName("복원 대상은 연습 DB로 고정되고 운영 DB를 대상으로 받지 않는다")
    void restrictsRestoreToPracticeDatabase() throws Exception {
        // when
        var script = Files.readString(DEPLOY.resolve("restore.sh"));

        // then
        assertThat(script).contains("mogum_restore_check");
        assertThat(script).contains("pg_restore --exit-on-error");
        assertThat(Pattern.compile("(?:-d|--dbname[= ])\\s*['\"]?mogum(?:['\"\\s]|$)").matcher(script).find()).isFalse();
        assertThat(script).doesNotContain("POSTGRES_DB");
        assertThat(script).as("latest는 모든 페이지를 합친 뒤 고른다").contains("--output json");
    }

    @Test
    @DisplayName("백업 타이머는 매일 한국 04시에 실행하고 놓친 실행을 보충한다")
    void schedulesPersistentDailyBackup() throws Exception {
        // when
        var timer = Files.readString(DEPLOY.resolve("systemd/mogum-backup.timer"));

        // then
        assertThat(timer).contains("OnCalendar=*-*-* 19:00:00 UTC");
        assertThat(timer).contains("Persistent=true");
        assertThat(timer).contains("WantedBy=timers.target");
    }

    @Test
    @DisplayName("백업 서비스는 root로 현재 릴리스의 스크립트를 실행한다")
    void runsCurrentBackupAsRootAfterDockerAndNetwork() throws Exception {
        // when
        var service = Files.readString(DEPLOY.resolve("systemd/mogum-backup.service"));

        // then
        assertThat(service).contains("Type=oneshot");
        assertThat(service).contains("User=root");
        assertThat(service).contains("EnvironmentFile=/etc/mogum/mogum.env");
        assertThat(service).contains("ExecStart=/opt/mogum/current/deploy/backup.sh");
        assertThat(service).contains("After=docker.service network-online.target");
    }

    @ParameterizedTest(name = "스크립트 {0}")
    @ValueSource(strings = {"backup.sh", "restore.sh"})
    @DisplayName("백업과 복원 스크립트는 bash 구문 검사를 통과한다")
    void passesBashSyntaxCheck(String name) throws Exception {
        // given
        var path = DEPLOY.resolve(name);

        // when
        var process = new ProcessBuilder("bash", "-n", path.toString()).redirectErrorStream(true).start();

        // then
        var output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as(output).isZero();
    }
}
