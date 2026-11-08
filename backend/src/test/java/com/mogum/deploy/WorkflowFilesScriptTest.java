package com.mogum.deploy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowFilesScriptTest {
    private static final Path WORKFLOW = Path.of("../.github/workflows/deploy.yml");
    private static final String SHA = "a".repeat(40);
    private static final String DIGEST = "sha256:" + "b".repeat(64);
    private static final String REGISTRY = "000000000000.dkr.ecr.ap-northeast-2.amazonaws.com";
    private static final Set<String> SECRETS = Set.of("AWS_DEPLOY_ROLE_ARN", "DEPLOY_BUCKET", "DEPLOY_INSTANCE_ID");

    @ParameterizedTest(name = "시나리오 {0}")
    @ValueSource(strings = {"push", "manual", "rollback", "invalid-sha", "uppercase-sha", "other-branch", "missing-vars",
            "invalid-region", "invalid-role", "invalid-bucket", "invalid-instance"})
    @Timeout(15)
    @DisplayName("AWS와 빌드 전에 입력과 변수를 검사한다")
    void validatesInputsBeforeAwsAndBuild(String scenario, @TempDir Path root) throws Exception {
        // given
        var environment = baseEnvironment();
        environment.put("EVENT_NAME", scenario.equals("push") ? "push" : "workflow_dispatch");
        environment.put("INPUT_SHA", switch (scenario) {
            case "rollback" -> "c".repeat(40);
            case "invalid-sha" -> "$(touch injected)";
            case "uppercase-sha" -> "A".repeat(40);
            default -> "";
        });
        if (scenario.equals("other-branch")) environment.put("SOURCE_REF", "refs/heads/other");
        switch (scenario) {
            case "invalid-region" -> environment.put("AWS_REGION", "invalid-region");
            case "invalid-role" -> environment.put("AWS_DEPLOY_ROLE_ARN", "arn:aws:iam::000000000000:user/invalid");
            case "invalid-bucket" -> environment.put("DEPLOY_BUCKET", "INVALID_BUCKET");
            case "invalid-instance" -> environment.put("DEPLOY_INSTANCE_ID", "invalid-instance");
        }
        if (scenario.equals("missing-vars")) {
            environment.put("DEPLOY_BUCKET", "");
            environment.put("API_ORIGIN", "");
        }
        // when
        var result = run("validate", root, environment, "");

        // then
        boolean success = Set.of("push", "manual", "rollback").contains(scenario);
        assertThat(result.status()).as(result.output()).isEqualTo(success ? 0 : 1);
        assertThat(Files.exists(root.resolve("injected"))).isFalse();
        assertThat(result.commands()).as("검사 중에는 AWS를 호출하지 않는다").isEqualTo("");
        assertThat(result.output()).as("오류 출력보다 먼저 계정 ID를 가린다").startsWith("::add-mask::000000000000\n");
        assertThat(result.output().substring(result.output().indexOf('\n') + 1)).doesNotContain("000000000000");
        if (success) {
            assertThat(result.output()).isEqualTo("::add-mask::000000000000\n");
            assertThat(result.environment()).contains("RELEASE_SHA=" + (scenario.equals("rollback") ? "c".repeat(40) : SHA));
            assertThat(result.outputs()).contains("redeploy=" + scenario.equals("rollback"));
        } else {
            assertThat(result.environment()).isEmpty();
            if (scenario.equals("missing-vars")) {
                assertThat(result.output()).contains("Missing repository secret: DEPLOY_BUCKET");
                assertThat(result.output()).contains("Missing repository variable: API_ORIGIN");
                assertThat(result.output()).doesNotContain("test-bucket");
                assertThat(result.output()).doesNotContain("example.invalid");
            }
        }
    }

    @ParameterizedTest(name = "시나리오 {0}")
    @ValueSource(strings = {"AWS_REGION", "API_ORIGIN", "AWS_DEPLOY_ROLE_ARN", "DEPLOY_BUCKET", "DEPLOY_INSTANCE_ID"})
    @Timeout(15)
    @DisplayName("누락된 설정은 종류와 이름만 출력한다")
    void reportsOnlyMissingSettingNames(String name, @TempDir Path root) throws Exception {
        // given
        var environment = baseEnvironment();
        environment.put(name, "");
        // when
        var result = run("validate", root, environment, "");

        // then
        assertThat(result.status()).isEqualTo(1);
        var mask = name.equals("AWS_DEPLOY_ROLE_ARN") ? "" : "::add-mask::000000000000\n";
        var kind = SECRETS.contains(name) ? "secret" : "variable";
        assertThat(result.output()).isEqualTo(mask + "Missing repository " + kind + ": " + name + "\n");
        assertThat(result.environment()).isEqualTo("");
        assertThat(result.commands()).isEqualTo("");
    }

    @ParameterizedTest(name = "시나리오 {0}")
    @ValueSource(strings = {"existing", "missing", "denied", "rollback-missing"})
    @Timeout(15)
    @DisplayName("기존 이미지는 지문을 재사용하고 권한 오류는 없는 이미지로 취급하지 않는다")
    void reusesDigestAndRejectsPermissionFailures(String scenario, @TempDir Path root) throws Exception {
        // given
        var environment = baseEnvironment();
        environment.put("REDEPLOY", Boolean.toString(scenario.equals("rollback-missing")));
        var fake = """
                if [[ $SCENARIO == denied ]]; then echo AccessDeniedException >&2; exit 1; fi
                if [[ $SCENARIO == *missing ]]; then echo ImageNotFoundException >&2; exit 1; fi
                printf '{"imageDetails":[{"imageDigest":"%s"}]}' "$TEST_DIGEST"
                """;
        environment.put("SCENARIO", scenario);
        // when
        var result = run("image", root, environment, fake);

        // then
        assertThat(result.status())
                .as(result.output()).isEqualTo(Set.of("denied", "rollback-missing").contains(scenario) ? 1 : 0);
        if (scenario.equals("existing")) {
            assertThat(result.outputs()).contains("exists=true");
            assertThat(result.environment()).contains("APP_IMAGE_REF=" + REGISTRY + "/mogum-app@" + DIGEST);
        }
        if (scenario.equals("missing")) assertThat(result.outputs()).contains("exists=false");
        if (scenario.equals("rollback-missing")) assertThat(result.output()).contains("image");
    }

    @ParameterizedTest(name = "시나리오 {0}")
    @ValueSource(strings = {"matching", "missing", "mismatched"})
    @Timeout(15)
    @DisplayName("롤백은 기존 묶음과 이미지 지문이 일치해야 한다")
    void requiresMatchingRollbackBundleAndDigest(String scenario, @TempDir Path root) throws Exception {
        // given
        var environment = baseEnvironment();
        environment.put("SCENARIO", scenario);
        environment.put("APP_IMAGE_REF", REGISTRY + "/mogum-app@" + DIGEST);
        var archive = Files.createDirectory(root.resolve("archive"));
        Files.writeString(archive.resolve("app-image.ref"), REGISTRY + "/mogum-app@"
                + (scenario.equals("mismatched") ? "sha256:" + "c".repeat(64) : DIGEST) + "\n");
        var tar = new ProcessBuilder("tar", "-czf", root.resolve("fixture.tar.gz").toString(), "-C", archive.toString(), "app-image.ref").start();
        assertThat(tar.waitFor()).isEqualTo(0);
        // when
        var result = run("inspect-release", root, environment, """
                [[ $SCENARIO != missing ]] || exit 1
                cp "$TEST_ROOT/fixture.tar.gz" "$4"
                """);

        // then
        assertThat(result.status()).as(result.output()).isEqualTo(scenario.equals("matching") ? 0 : 1);
        if (scenario.equals("missing")) assertThat(result.output()).contains("bundle");
    }

    @ParameterizedTest(name = "시나리오 {0}")
    @ValueSource(strings = {"Success", "Failed", "TimedOut", "DeliveryTimedOut", "poll-timeout"})
    @Timeout(15)
    @DisplayName("SSM 결과와 두 출력을 확인하고 성공만 통과한다")
    void waitsForSuccessfulSsmResultAndShowsBothOutputs(String scenario, @TempDir Path root) throws Exception {
        // given
        var environment = baseEnvironment();
        environment.put("SCENARIO", scenario);
        // when
        var result = run("ssm", root, environment, """
                if [[ $2 == send-command ]]; then
                    printf 'test-command-id'
                elif [[ ! -f $TEST_ROOT/polled ]]; then
                    touch "$TEST_ROOT/polled"
                    echo InvocationDoesNotExist >&2
                    exit 1
                else
                    printf '{"Status":"%s","ResponseCode":0,"StandardOutputContent":"deploy-output","StandardErrorContent":"deploy-error"}' "$SCENARIO"
                fi
                """);

        // then
        assertThat(result.status()).as(result.output()).isEqualTo(scenario.equals("Success") ? 0 : 1);
        if (!scenario.equals("poll-timeout")) {
            assertThat(result.output()).contains("deploy-output");
            assertThat(result.output()).contains("deploy-error");
        }
        var parameters = Files.readString(root.resolve("ssm-parameters.json"));
        assertThat(parameters).contains("AWS_REGION");
        assertThat(parameters).contains("/opt/mogum/releases");
        assertThat(parameters).contains("deploy/deploy.sh");
        assertThat(parameters).contains("id -u");
        assertThat(parameters).contains("executionTimeout");
        assertThat(result.commands()).doesNotContain("cancel-command");
    }

    @ParameterizedTest(name = "시나리오 {0}")
    @ValueSource(strings = {"200", "503"})
    @Timeout(15)
    @DisplayName("공개 HTTPS는 200만 통과한다")
    void acceptsOnlySuccessfulPublicHealth(String status, @TempDir Path root) throws Exception {
        // given
        var environment = baseEnvironment();
        environment.put("SCENARIO", status);
        // when
        var result = run("health", root, environment, "");

        // then
        assertThat(result.status()).as(result.output()).isEqualTo(status.equals("200") ? 0 : 1);
        assertThat(result.commands()).contains("https://api.example.invalid/actuator/health");
    }

    private Result run(String id, Path root, Map<String, String> environment, String fakeAws) throws Exception {
        var bin = Files.createDirectory(root.resolve("bin"));
        var output = Files.createFile(root.resolve("github-output"));
        var env = Files.createFile(root.resolve("github-env"));
        Files.writeString(bin.resolve("aws"), "#!/usr/bin/env bash\nset -euo pipefail\n"
                + "printf '%s\\n' \"$*\" >> \"$TEST_ROOT/commands\"\n" + fakeAws);
        Files.writeString(bin.resolve("sleep"), "#!/usr/bin/env bash\nexit 0\n");
        Files.writeString(bin.resolve("curl"), "#!/usr/bin/env bash\n"
                + "printf '%s\\n' \"$*\" >> \"$TEST_ROOT/commands\"\nprintf '%s' \"$SCENARIO\"\n");
        for (var name : List.of("aws", "sleep", "curl")) assertThat(bin.resolve(name).toFile().setExecutable(true)).isTrue();
        var source = script(id);
        if (environment.getOrDefault("SCENARIO", "").equals("poll-timeout")) source = source.replace("SECONDS + 1200", "SECONDS + 0");
        if (id.equals("health")) source = source.replace("SECONDS + 60", "SECONDS + 1");
        var script = Files.writeString(root.resolve("run.sh"), source);
        var builder = new ProcessBuilder("bash", "-e", "-o", "pipefail", script.toString()).directory(root.toFile()).redirectErrorStream(true);
        builder.environment().putAll(environment);
        builder.environment().putAll(Map.of("TEST_ROOT", root.toString(), "RUNNER_TEMP", root.toString(),
                "TEST_DIGEST", DIGEST, "GITHUB_OUTPUT", output.toString(), "GITHUB_ENV", env.toString(),
                "PATH", bin + ":" + System.getenv("PATH")));
        var process = builder.start();
        var text = new String(process.getInputStream().readAllBytes());
        int status = process.waitFor();
        return new Result(status, text, Files.readString(output), Files.readString(env),
                Files.exists(root.resolve("commands")) ? Files.readString(root.resolve("commands")) : "");
    }

    private static Map<String, String> baseEnvironment() {
        return new java.util.HashMap<>(Map.ofEntries(
                Map.entry("AWS_REGION", "ap-northeast-2"), Map.entry("AWS_DEPLOY_ROLE_ARN", "arn:aws:iam::000000000000:role/mogum-deploy"),
                Map.entry("DEPLOY_BUCKET", "test-bucket"), Map.entry("DEPLOY_INSTANCE_ID", "i-00000000000000000"),
                Map.entry("API_ORIGIN", "https://api.example.invalid"), Map.entry("COMMIT_SHA", SHA),
                Map.entry("SOURCE_REF", "refs/heads/main"), Map.entry("REGISTRY", REGISTRY),
                Map.entry("APP_IMAGE_REF", REGISTRY + "/mogum-app@" + DIGEST),
                Map.entry("RELEASE_SHA", SHA), Map.entry("REDEPLOY", "false")));
    }

    private Map<String, Object> workflow() throws Exception { return new Yaml().load(Files.readString(WORKFLOW)); }
    private Map<String, Object> job() throws Exception { return map(map(workflow().get("jobs")).get("deploy")); }
    private List<Map<String, Object>> steps() throws Exception {
        return list(job().get("steps")).stream().map(WorkflowFilesScriptTest::map).toList();
    }
    private Map<String, Object> step(String id) throws Exception {
        return steps().stream().filter(step -> id.equals(step.get("id"))).findFirst().orElseThrow();
    }
    private String script(String id) throws Exception { return step(id).get("run").toString(); }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    private static List<?> list(Object value) { return (List<?>) value; }
    private record Result(int status, String output, String outputs, String environment, String commands) {}
}
