package com.mogum.deploy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class DeployFilesJournalTest {
    private static final Path DEPLOY = Path.of("../deploy");

    @Test
    @DisplayName("저널은 디스크에 보존하고 파일 회전까지 총 14일과 500MiB 이하다")
    void limitsJournalRetentionAndDiskUsage() throws Exception {
        // given
        var settings = new HashMap<String, String>();
        String section = "";

        // when
        var lines = Files.readAllLines(DEPLOY.resolve("journald/mogum.conf"));

        // then
        for (var raw : lines) {
            var line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
            if (line.startsWith("[")) {
                assertThat(line).isEqualTo("[Journal]");
                section = line;
                continue;
            }
            assertThat(section).isEqualTo("[Journal]");
            var parts = line.split("=", 2);
            assertThat(parts.length).isEqualTo(2);
            assertThat(settings.put(parts[0].strip(), parts[1].strip())).as("중복 설정: " + parts[0]).isNull();
        }
        assertThat(settings.keySet()).isEqualTo(Set.of("Storage", "SystemMaxUse", "MaxRetentionSec", "MaxFileSec"));
        assertThat(settings.get("Storage")).isEqualTo("persistent");
        long maximumBytes = journalBytes(settings.get("SystemMaxUse"));
        assertThat(maximumBytes).isPositive().isLessThanOrEqualTo(500L * 1024 * 1024);
        long retention = journalSeconds(settings.get("MaxRetentionSec"));
        long fileDuration = journalSeconds(settings.get("MaxFileSec"));
        assertThat(retention).as("0은 기간 제한을 끈다").isPositive();
        assertThat(fileDuration).as("0은 기간 제한을 끈다").isPositive();
        assertThat(retention + fileDuration).as("파일 회전 기간도 보관 상한에 포함한다")
                .isLessThanOrEqualTo(14L * 24 * 60 * 60);
    }

    private static long journalSeconds(String value) {
        var matcher = Pattern.compile("([0-9]+)\\s*([a-z]+)?").matcher(value);
        assertThat(matcher).as("기간 값: " + value).matches();
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
        assertThat(matcher).as("크기 값: " + value).matches();
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
