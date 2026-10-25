package com.mogum.deploy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowFilesTest {
    private static final Path WORKFLOW = Path.of("../.github/workflows/deploy.yml");
    private static final String SHA = "a".repeat(40);
    private static final String DIGEST = "sha256:" + "b".repeat(64);
    private static final String REGISTRY = "000000000000.dkr.ecr.ap-northeast-2.amazonaws.com";
    private static final Set<String> VARIABLES = Set.of("DEPLOY_ENABLED", "AWS_REGION", "AWS_DEPLOY_ROLE_ARN",
            "DEPLOY_BUCKET", "DEPLOY_INSTANCE_ID", "API_ORIGIN");

    @Test
    void main과_세_경로의_push_및_SHA_선택_수동_실행만_받는다() throws Exception {
        // SnakeYAML의 YAML 1.1 on=true 해석에 의존하지 않도록 워크플로 키는 인용한다.
        var triggers = map(workflow().get("on"));
        assertEquals(Set.of("push", "workflow_dispatch"), triggers.keySet());
        var push = map(triggers.get("push"));
        assertEquals(Set.of("branches", "paths"), push.keySet());
        assertEquals(List.of("main"), push.get("branches"));
        assertEquals(Set.of("backend/**", "deploy/**", ".github/workflows/deploy.yml"), Set.copyOf(list(push.get("paths"))));
        var inputs = map(map(triggers.get("workflow_dispatch")).get("inputs"));
        assertEquals(Set.of("sha"), inputs.keySet());
        assertEquals(false, map(inputs.get("sha")).get("required"));
        assertEquals("string", map(inputs.get("sha")).get("type"));
    }

    @Test
    void 최소_권한과_취소하지_않는_단일_배포_그룹을_쓴다() throws Exception {
        assertEquals(Map.of("contents", "read", "id-token", "write"), workflow().get("permissions"));
        var concurrency = map(workflow().get("concurrency"));
        assertEquals(false, concurrency.get("cancel-in-progress"));
        assertFalse(concurrency.get("group").toString().isBlank());
        assertFalse(concurrency.get("group").toString().contains("${{"));
        assertEquals(Set.of("deploy"), map(workflow().get("jobs")).keySet());
        assertEquals("${{ vars.DEPLOY_ENABLED == 'true' }}", job().get("if"));
        assertEquals("ubuntu-24.04-arm", job().get("runs-on"));
        for (var value : map(workflow().get("jobs")).values()) {
            assertFalse(map(value).containsKey("environment"));
            assertFalse(map(value).containsKey("permissions"));
        }
    }

    @Test
    void 변수는_vars이고_OIDC와_존재를_확인한_공식_액션만_쓴다() throws Exception {
        var text = Files.readString(WORKFLOW);
        for (var variable : VARIABLES) assertTrue(text.contains("vars." + variable), variable);
        assertFalse(text.contains("secrets."));
        assertFalse(text.contains("aws-access-key-id"));
        assertFalse(text.contains("aws-secret-access-key"));
        var actions = new ArrayList<String>();
        for (var step : steps()) {
            if (step.containsKey("uses")) actions.add(step.get("uses").toString());
            if (step.containsKey("run")) {
                var script = step.get("run").toString();
                assertFalse(script.contains("${{"), "식은 run 본문 대신 env에 전달한다");
                assertFalse(script.contains("set -x"));
            }
        }
        assertEquals(Set.of("actions/checkout@v5", "actions/setup-java@v5",
                "aws-actions/configure-aws-credentials@v5"), Set.copyOf(actions));
        assertEquals("21", map(step("java").get("with")).get("java-version"));
        var credentials = map(step("credentials").get("with"));
        assertEquals("${{ vars.AWS_DEPLOY_ROLE_ARN }}", credentials.get("role-to-assume"));
        assertEquals("${{ vars.AWS_REGION }}", credentials.get("aws-region"));
        assertEquals("validate", steps().getFirst().get("id"));
    }

    @Test
    void 검사한_이미지를_한번만_빌드하고_기존_SHA는_빌드와_검사와_push를_건너뛴다() throws Exception {
        assertTrue(script("build").contains("./backend/gradlew -p backend build"));
        assertEquals(1, steps().stream().filter(step -> step.containsKey("run"))
                .flatMap(step -> step.get("run").toString().lines()).filter(line -> line.strip().startsWith("docker build ")).count());
        assertTrue(script("build").contains("docker build -t \"$LOCAL_IMAGE\" backend"));
        assertTrue(script("build").contains("imageTest -PappImage=\"$LOCAL_IMAGE\""));
        assertTrue(script("publish").contains("docker tag \"$LOCAL_IMAGE\" \"$REGISTRY/mogum-app:$RELEASE_SHA\""));
        assertTrue(script("publish").contains("docker push \"$REGISTRY/mogum-app:$RELEASE_SHA\""));
        for (var id : List.of("java", "build", "publish")) {
            assertEquals("${{ steps.image.outputs.exists == 'false' }}", step(id).get("if"));
        }
        for (var id : List.of("checkout", "bundle")) {
            assertEquals("${{ steps.validate.outputs.redeploy == 'false' }}", step(id).get("if"));
        }
        assertTrue(script("bundle").contains("deploy app-image.ref"));
        assertFalse(Files.readString(WORKFLOW).contains(":latest"));
        assertTrue(script("inspect-release").contains("tar -xOf"));
        assertTrue(script("inspect-release").contains("$APP_IMAGE_REF"));
    }

    @Test
    void 워크플로도_Gradle_test의_입력이다() throws Exception {
        assertTrue(Files.readString(Path.of("build.gradle")).contains("inputs.dir('../.github/workflows')"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"push", "manual", "rollback", "invalid-sha", "uppercase-sha", "other-branch", "missing-vars"})
    @Timeout(15)
    void AWS와_빌드_전에_입력과_변수를_검사한다(String scenario, @TempDir Path root) throws Exception {
        var environment = baseEnvironment();
        environment.put("EVENT_NAME", scenario.equals("push") ? "push" : "workflow_dispatch");
        environment.put("INPUT_SHA", switch (scenario) {
            case "rollback" -> "c".repeat(40);
            case "invalid-sha" -> "$(touch injected)";
            case "uppercase-sha" -> "A".repeat(40);
            default -> "";
        });
        if (scenario.equals("other-branch")) environment.put("SOURCE_REF", "refs/heads/other");
        if (scenario.equals("missing-vars")) {
            environment.put("DEPLOY_BUCKET", "");
            environment.put("API_ORIGIN", "");
        }
        var result = run("validate", root, environment, "");
        boolean success = Set.of("push", "manual", "rollback").contains(scenario);
        assertEquals(success ? 0 : 1, result.status(), result.output());
        assertFalse(Files.exists(root.resolve("injected")));
        if (success) {
            assertTrue(result.environment().contains("RELEASE_SHA=" + (scenario.equals("rollback") ? "c".repeat(40) : SHA)));
            assertTrue(result.outputs().contains("redeploy=" + scenario.equals("rollback")));
        } else {
            assertTrue(result.environment().isEmpty());
            if (scenario.equals("missing-vars")) {
                assertTrue(result.output().contains("DEPLOY_BUCKET"));
                assertTrue(result.output().contains("API_ORIGIN"));
                assertFalse(result.output().contains("test-bucket"));
                assertFalse(result.output().contains("example.invalid"));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"existing", "missing", "denied", "rollback-missing"})
    @Timeout(15)
    void 기존_이미지는_지문을_재사용하고_권한_오류는_없는_이미지로_취급하지_않는다(String scenario, @TempDir Path root) throws Exception {
        var environment = baseEnvironment();
        environment.put("REDEPLOY", Boolean.toString(scenario.equals("rollback-missing")));
        var fake = """
                if [[ $SCENARIO == denied ]]; then echo AccessDeniedException >&2; exit 1; fi
                if [[ $SCENARIO == *missing ]]; then echo ImageNotFoundException >&2; exit 1; fi
                printf '{"imageDetails":[{"imageDigest":"%s"}]}' "$TEST_DIGEST"
                """;
        environment.put("SCENARIO", scenario);
        var result = run("image", root, environment, fake);
        assertEquals(Set.of("denied", "rollback-missing").contains(scenario) ? 1 : 0, result.status(), result.output());
        if (scenario.equals("existing")) {
            assertTrue(result.outputs().contains("exists=true"));
            assertTrue(result.environment().contains("APP_IMAGE_REF=" + REGISTRY + "/mogum-app@" + DIGEST));
        }
        if (scenario.equals("missing")) assertTrue(result.outputs().contains("exists=false"));
        if (scenario.equals("rollback-missing")) assertTrue(result.output().contains("image"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"matching", "missing", "mismatched"})
    @Timeout(15)
    void 롤백은_기존_묶음과_이미지_지문이_일치해야_한다(String scenario, @TempDir Path root) throws Exception {
        var environment = baseEnvironment();
        environment.put("SCENARIO", scenario);
        environment.put("APP_IMAGE_REF", REGISTRY + "/mogum-app@" + DIGEST);
        var archive = Files.createDirectory(root.resolve("archive"));
        Files.writeString(archive.resolve("app-image.ref"), REGISTRY + "/mogum-app@"
                + (scenario.equals("mismatched") ? "sha256:" + "c".repeat(64) : DIGEST) + "\n");
        var tar = new ProcessBuilder("tar", "-czf", root.resolve("fixture.tar.gz").toString(), "-C", archive.toString(), "app-image.ref").start();
        assertEquals(0, tar.waitFor());
        var result = run("inspect-release", root, environment, """
                [[ $SCENARIO != missing ]] || exit 1
                cp "$TEST_ROOT/fixture.tar.gz" "$4"
                """);
        assertEquals(scenario.equals("matching") ? 0 : 1, result.status(), result.output());
        if (scenario.equals("missing")) assertTrue(result.output().contains("bundle"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Success", "Failed", "TimedOut", "DeliveryTimedOut", "poll-timeout"})
    @Timeout(15)
    void SSM_결과와_두_출력을_확인하고_성공만_통과한다(String scenario, @TempDir Path root) throws Exception {
        var environment = baseEnvironment();
        environment.put("SCENARIO", scenario);
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
        assertEquals(scenario.equals("Success") ? 0 : 1, result.status(), result.output());
        if (!scenario.equals("poll-timeout")) {
            assertTrue(result.output().contains("deploy-output"));
            assertTrue(result.output().contains("deploy-error"));
        }
        var parameters = Files.readString(root.resolve("ssm-parameters.json"));
        assertTrue(parameters.contains("AWS_REGION"));
        assertTrue(parameters.contains("/opt/mogum/releases"));
        assertTrue(parameters.contains("deploy/deploy.sh"));
        assertTrue(parameters.contains("id -u"));
        assertTrue(parameters.contains("executionTimeout"));
        assertFalse(result.commands().contains("cancel-command"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"200", "503"})
    @Timeout(15)
    void 공개_HTTPS는_200만_통과한다(String status, @TempDir Path root) throws Exception {
        var environment = baseEnvironment();
        environment.put("SCENARIO", status);
        var result = run("health", root, environment, "");
        assertEquals(status.equals("200") ? 0 : 1, result.status(), result.output());
        assertTrue(result.commands().contains("https://api.example.invalid/actuator/health"));
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
        for (var name : List.of("aws", "sleep", "curl")) assertTrue(bin.resolve(name).toFile().setExecutable(true));
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
        return list(job().get("steps")).stream().map(WorkflowFilesTest::map).toList();
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
