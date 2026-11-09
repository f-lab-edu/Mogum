package com.mogum.deploy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class CiWorkflowFilesTest {
    private static final Path WORKFLOW = Path.of("../.github/workflows/ci.yml");

    @Test
    @DisplayName("필터 없이 모든 PR을 검사하고 권한 있는 PR 트리거는 받지 않는다")
    void acceptsOnlyUnfilteredPullRequests() throws Exception {
        // when
        var triggers = map(workflow().get("on"));

        // then
        assertThat(triggers).containsOnlyKeys("pull_request");
        assertThat(triggers.get("pull_request")).isNull();
    }

    @Test
    @DisplayName("읽기 권한만 사용하고 비밀과 변수 및 배포 환경을 참조하지 않는다")
    void usesOnlyContentsReadWithoutSecretsOrEnvironments() throws Exception {
        // given
        var text = Files.readString(WORKFLOW);

        // when
        var definition = workflow();

        // then
        assertThat(definition.get("permissions")).isEqualTo(Map.of("contents", "read"));
        for (var value : map(definition.get("jobs")).values()) {
            assertThat(map(value)).doesNotContainKeys("permissions", "environment");
        }
        assertThat(text).doesNotContain("secrets.", "vars.");
    }

    @Test
    @DisplayName("세 작업의 러너와 제한 시간을 정하고 같은 PR의 이전 실행을 취소한다")
    void boundsThreeJobsAndCancelsPreviousRunsForTheSamePullRequest() throws Exception {
        // when
        var definition = workflow();

        // then
        assertThat(definition.get("name")).isEqualTo("CI");
        var concurrency = map(definition.get("concurrency"));
        assertThat(concurrency.get("group").toString()).contains("${{ github.event.pull_request.number }}");
        assertThat(concurrency.get("cancel-in-progress")).isEqualTo(true);
        var jobs = map(definition.get("jobs"));
        assertThat(jobs).containsOnlyKeys("backend", "frontend", "ci-ok");
        var timeouts = Map.of("backend", 30, "frontend", 15, "ci-ok", 5);
        for (var id : timeouts.keySet()) {
            var job = map(jobs.get(id));
            assertThat(job.get("runs-on")).isEqualTo("ubuntu-24.04");
            assertThat(job.get("timeout-minutes")).isEqualTo(timeouts.get(id));
        }
        assertThat(map(jobs.get("backend"))).doesNotContainKeys("if", "needs");
        assertThat(map(jobs.get("frontend"))).doesNotContainKeys("if", "needs");
    }

    @Test
    @DisplayName("ci-ok라는 검사 이름을 유지하고 선행 작업 결과를 항상 환경 변수로 받는다")
    void alwaysAggregatesBothJobsUnderItsJobId() throws Exception {
        // when
        var aggregate = job("ci-ok");

        // then
        assertThat(aggregate).doesNotContainKey("name");
        assertThat(aggregate.get("needs")).isEqualTo(List.of("backend", "frontend"));
        assertThat(aggregate.get("if")).isEqualTo("always()");
        var steps = steps(aggregate);
        assertThat(steps).hasSize(1);
        assertThat(steps.getFirst().get("env")).isEqualTo(Map.of(
                "BACKEND_RESULT", "${{ needs.backend.result }}",
                "FRONTEND_RESULT", "${{ needs.frontend.result }}"));
        assertThat(steps.getFirst().get("run").toString())
                .contains("\"$BACKEND_RESULT\"", "\"$FRONTEND_RESULT\"", "success", "exit 1")
                .doesNotContain("${{");
    }

    @Test
    @DisplayName("두 작업이 모두 성공일 때만 집계를 통과시키고 실패와 취소와 건너뜀은 막는다")
    void passesOnlyWhenBothJobResultsAreSuccess() throws Exception {
        // given
        var script = steps(job("ci-ok")).getFirst().get("run").toString();
        var results = List.of("success", "failure", "cancelled", "skipped");
        var expected = new ArrayList<Integer>();
        for (var backend : results) {
            for (var frontend : results) {
                expected.add(backend.equals("success") && frontend.equals("success") ? 0 : 1);
            }
        }

        // when
        var exits = aggregateExitCodes(script, results);

        // then
        assertThat(exits).isEqualTo(expected);
    }

    @Test
    @DisplayName("허용한 액션 다섯 개를 전체 SHA와 해당 릴리스 주석으로 고정한다")
    void pinsAllowedActionsToFullShasAndReleaseComments() throws Exception {
        // given
        var text = Files.readString(WORKFLOW);
        var releases = Map.of(
                "actions/checkout@fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09", "v5.1.0",
                "actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961", "v5.7.0",
                "gradle/actions/setup-gradle@3f5f9adaf7d9fecd50b5935e54106014257a94e6", "v6.4.0",
                "actions/setup-node@949feb2413d6458794dcd2491c4babbbce0c15c1", "v7.1.0",
                "actions/upload-artifact@cf430e030ddbb5b0abf93d22962f4752f3646cd9", "v7.0.2");

        // when
        var definition = workflow();

        // then
        var actions = new ArrayList<String>();
        for (var value : map(definition.get("jobs")).values()) {
            for (var step : steps(map(value))) {
                if (step.containsKey("uses")) actions.add(step.get("uses").toString());
                if (step.containsKey("run")) assertThat(step.get("run").toString()).doesNotContain("${{");
            }
        }
        assertThat(Set.copyOf(actions)).isEqualTo(releases.keySet());
        for (var action : actions) {
            assertThat(action).matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*@[0-9a-f]{40}");
        }
        var lines = text.lines().filter(line -> line.matches("\\s*(?:-\\s+)?uses\\s*:.*")).toList();
        assertThat(lines).hasSize(actions.size());
        for (var line : lines) {
            assertThat(releases.entrySet().stream().anyMatch(release -> line.matches(
                    "\\s*(?:-\\s+)?uses: " + Pattern.quote(release.getKey())
                            + " # " + Pattern.quote(release.getValue()) + "\\s*")))
                    .as("액션 SHA와 릴리스 주석: " + line).isTrue();
        }
    }

    @Test
    @DisplayName("백엔드는 Java 21로 테스트만 실행하고 실패할 때만 보고서를 7일 보관한다")
    void testsBackendWithoutBuildingImagesAndUploadsReportsOnFailure() throws Exception {
        // when
        var steps = steps(job("backend"));

        // then
        assertThat(steps).hasSize(5);
        assertThat(steps.get(0).get("uses").toString()).startsWith("actions/checkout@");
        assertThat(map(steps.get(0).get("with"))).containsEntry("persist-credentials", false);
        assertThat(steps.get(1).get("uses").toString()).startsWith("actions/setup-java@");
        assertThat(steps.get(1).get("with")).isEqualTo(Map.of("distribution", "temurin", "java-version", "21"));
        assertThat(steps.get(2).get("uses").toString()).startsWith("gradle/actions/setup-gradle@");
        assertThat(steps.get(3).get("run")).isEqualTo("./backend/gradlew -p backend test");
        assertThat(steps.get(4).get("uses").toString()).startsWith("actions/upload-artifact@");
        assertThat(steps.get(4).get("if")).isEqualTo("failure()");
        assertThat(map(steps.get(4).get("with")))
                .containsEntry("path", "backend/build/reports/tests/test")
                .containsEntry("retention-days", 7);
        assertThat(steps.toString()).doesNotContain("imageTest", "docker");
        for (var step : steps.subList(0, 4)) assertThat(step).doesNotContainKey("if");
    }

    @Test
    @DisplayName("화면은 nvmrc의 Node와 npm 캐시를 쓰고 설치와 린트와 테스트와 빌드를 순서대로 실행한다")
    void checksFrontendInOrderUsingTheDeclaredNodeVersion() throws Exception {
        // when
        var frontend = job("frontend");

        // then
        assertThat(map(map(frontend.get("defaults")).get("run")))
                .containsEntry("working-directory", "frontend");
        var steps = steps(frontend);
        assertThat(steps).hasSize(6);
        assertThat(steps.get(0).get("uses").toString()).startsWith("actions/checkout@");
        assertThat(map(steps.get(0).get("with"))).containsEntry("persist-credentials", false);
        assertThat(steps.get(1).get("uses").toString()).startsWith("actions/setup-node@");
        assertThat(steps.get(1).get("with")).isEqualTo(Map.of(
                "node-version-file", "frontend/.nvmrc", "cache", "npm",
                "cache-dependency-path", "frontend/package-lock.json"));
        assertThat(steps.subList(2, 6).stream().map(step -> step.get("run")).toList())
                .containsExactly("npm ci", "npm run lint", "npm run test", "npm run build");
        for (var step : steps) assertThat(step).doesNotContainKey("if");
    }

    @Test
    @DisplayName("화면 Node 버전은 22 한 줄로 고정한다")
    void declaresNode22() throws Exception {
        // when
        var version = Files.readString(Path.of("../frontend/.nvmrc"));

        // then
        assertThat(version).isEqualTo("22\n");
    }

    private static List<Integer> aggregateExitCodes(String script, List<String> results) throws Exception {
        var exits = new ArrayList<Integer>();
        for (var backend : results) {
            for (var frontend : results) {
                var process = new ProcessBuilder("bash", "-e", "-c", script);
                process.environment().put("BACKEND_RESULT", backend);
                process.environment().put("FRONTEND_RESULT", frontend);
                exits.add(process.start().waitFor());
            }
        }
        return exits;
    }

    private static Map<String, Object> workflow() throws Exception {
        return map(new Yaml().load(Files.readString(WORKFLOW)));
    }

    private static Map<String, Object> job(String id) throws Exception {
        return map(map(workflow().get("jobs")).get(id));
    }

    private static List<Map<String, Object>> steps(Map<String, Object> job) {
        return list(job.get("steps")).stream().map(CiWorkflowFilesTest::map).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }
}
