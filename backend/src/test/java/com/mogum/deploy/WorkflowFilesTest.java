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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowFilesTest {
    private static final Path WORKFLOW = Path.of("../.github/workflows/deploy.yml");
    private static final Set<String> VARIABLES = Set.of("DEPLOY_ENABLED", "AWS_REGION", "API_ORIGIN");
    private static final Set<String> SECRETS = Set.of("AWS_DEPLOY_ROLE_ARN", "DEPLOY_BUCKET", "DEPLOY_INSTANCE_ID");

    @Test
    @DisplayName("main과 세 경로의 push 및 SHA 선택 수동 실행만 받는다")
    void acceptsMainPathPushesAndManualShaSelection() throws Exception {
        // SnakeYAML의 YAML 1.1 on=true 해석에 의존하지 않도록 워크플로 키는 인용한다.
        // when
        var triggers = map(workflow().get("on"));

        // then
        assertThat(triggers.keySet()).isEqualTo(Set.of("push", "workflow_dispatch"));
        var push = map(triggers.get("push"));
        assertThat(push.keySet()).isEqualTo(Set.of("branches", "paths"));
        assertThat(push.get("branches")).isEqualTo(List.of("main"));
        assertThat(Set.copyOf(list(push.get("paths"))))
                .isEqualTo(Set.of("backend/**", "deploy/**", ".github/workflows/deploy.yml"));
        var inputs = map(map(triggers.get("workflow_dispatch")).get("inputs"));
        assertThat(inputs.keySet()).isEqualTo(Set.of("sha"));
        assertThat(map(inputs.get("sha")).get("required")).isEqualTo(false);
        assertThat(map(inputs.get("sha")).get("type")).isEqualTo("string");
    }

    @Test
    @DisplayName("최소 권한과 취소하지 않는 단일 배포 그룹을 쓴다")
    void serializesDeploymentsWithMinimalPermissions() throws Exception {
        // when
        var definition = workflow();

        // then
        assertThat(definition.get("permissions")).isEqualTo(Map.of("contents", "read", "id-token", "write"));
        var concurrency = map(workflow().get("concurrency"));
        assertThat(concurrency.get("cancel-in-progress")).isEqualTo(false);
        assertThat(concurrency.get("group").toString()).isNotBlank();
        assertThat(concurrency.get("group").toString()).doesNotContain("${{");
        assertThat(map(workflow().get("jobs")).keySet()).isEqualTo(Set.of("deploy"));
        assertThat(job().get("if")).isEqualTo("${{ vars.DEPLOY_ENABLED == 'true' }}");
        assertThat(job().get("runs-on")).isEqualTo("ubuntu-24.04-arm");
        for (var value : map(workflow().get("jobs")).values()) {
            assertThat(map(value)).doesNotContainKey("environment");
            assertThat(map(value)).doesNotContainKey("permissions");
        }
    }

    @Test
    @DisplayName("공개 변수와 식별자 Secrets를 구분하고 OIDC와 공식 액션만 쓴다")
    void usesOidcAndPinnedOfficialActions() throws Exception {
        // when
        var text = Files.readString(WORKFLOW);

        // then
        assertThat(Pattern.compile("vars\\.([A-Za-z0-9_]+)").matcher(text).results()
                .map(match -> match.group(1)).collect(Collectors.toSet())).isEqualTo(VARIABLES);
        assertThat(Pattern.compile("secrets\\.([A-Za-z0-9_]+)").matcher(text).results()
                .map(match -> match.group(1)).collect(Collectors.toSet())).isEqualTo(SECRETS);
        var environment = map(job().get("env"));
        for (var secret : SECRETS) assertThat(environment.get(secret)).isEqualTo("${{ secrets." + secret + " }}");
        for (var variable : List.of("AWS_REGION", "API_ORIGIN")) {
            assertThat(environment.get(variable)).isEqualTo("${{ vars." + variable + " }}");
        }
        assertThat(text).doesNotContain("aws-access-key-id");
        assertThat(text).doesNotContain("aws-secret-access-key");
        var actions = new ArrayList<String>();
        for (var step : steps()) {
            if (step.containsKey("uses")) actions.add(step.get("uses").toString());
            if (step.containsKey("run")) {
                var script = step.get("run").toString();
                assertThat(script).as("식은 run 본문 대신 env에 전달한다").doesNotContain("${{");
                assertThat(script).doesNotContain("set -x");
            }
        }
        var actionReleases = Map.of(
                "actions/checkout@fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09", "v5.1.0",
                "actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961", "v5.7.0",
                "aws-actions/configure-aws-credentials@61815dcd50bd041e203e49132bacad1fd04d2708", "v5.1.1");
        for (var action : actions) {
            assertThat(action)
                    .as("액션은 전체 40자 소문자 커밋 SHA로 고정한다: " + action).matches("[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+@[0-9a-f]{40}");
        }
        assertThat(Set.copyOf(actions)).isEqualTo(actionReleases.keySet());
        var usesLines = text.lines().filter(line -> line.matches("\\s*(?:-\\s+)?uses\\s*:.*")).toList();
        assertThat(usesLines.size()).isEqualTo(actions.size());
        for (var line : usesLines) {
            assertThat(actionReleases.entrySet().stream().anyMatch(action -> line.matches(
                    "\\s*(?:-\\s+)?uses: " + Pattern.quote(action.getKey())
                            + " # " + Pattern.quote(action.getValue()) + "\\s*")))
                    .as("uses 줄 끝에 고정한 릴리스 주석을 남긴다: " + line).isTrue();
        }
        assertThat(map(step("java").get("with")).get("java-version")).isEqualTo("21");
        var credentials = map(step("credentials").get("with"));
        assertThat(credentials.get("role-to-assume")).isEqualTo("${{ secrets.AWS_DEPLOY_ROLE_ARN }}");
        assertThat(credentials.get("aws-region")).isEqualTo("${{ vars.AWS_REGION }}");
        assertThat(credentials.get("mask-aws-account-id")).isEqualTo(true);
        assertThat(steps().getFirst().get("id")).isEqualTo("validate");
    }

    @Test
    @DisplayName("계정 ID는 레지스트리 구성과 AWS 호출 전에 가림 등록한다")
    void masksAccountBeforeRegistryAndAwsCalls() throws Exception {
        // when
        var validation = script("validate");

        // then
        int mask = validation.indexOf("::add-mask::");
        assertThat(mask).isNotNegative();
        assertThat(mask).isLessThan(validation.indexOf("registry="));
        assertThat(mask).isLessThan(validation.indexOf("Missing repository"));
        var scripts = steps().stream().filter(step -> step.containsKey("run"))
                .map(step -> step.get("run").toString()).collect(Collectors.joining("\n"));
        int aws = scripts.indexOf("aws ");
        assertThat(aws).isNotNegative();
        assertThat(scripts.indexOf("::add-mask::")).isLessThan(aws);
        assertThat(steps().indexOf(step("validate")) < steps().indexOf(step("credentials"))).isTrue();
    }

    @Test
    @DisplayName("검사한 이미지를 한번만 빌드하고 기존 SHA는 빌드와 검사와 push를 건너뛴다")
    void buildsAndTestsImageOnceAndReusesExistingSha() throws Exception {
        // when
        var build = script("build");

        // then
        assertThat(build).contains("./backend/gradlew -p backend build");
        assertThat(steps().stream().filter(step -> step.containsKey("run"))
                .flatMap(step -> step.get("run").toString().lines()).filter(line -> line.strip().startsWith("docker build ")).count()).isEqualTo(1);
        assertThat(script("build")).contains("docker build -t \"$LOCAL_IMAGE\" backend");
        assertThat(script("build")).contains("imageTest -PappImage=\"$LOCAL_IMAGE\"");
        assertThat(script("publish")).contains("docker tag \"$LOCAL_IMAGE\" \"$REGISTRY/mogum-app:$RELEASE_SHA\"");
        assertThat(script("publish")).contains("docker push \"$REGISTRY/mogum-app:$RELEASE_SHA\"");
        for (var id : List.of("java", "build", "publish")) {
            assertThat(step(id).get("if")).isEqualTo("${{ steps.image.outputs.exists == 'false' }}");
        }
        for (var id : List.of("checkout", "bundle")) {
            assertThat(step(id).get("if")).isEqualTo("${{ steps.validate.outputs.redeploy == 'false' }}");
        }
        assertThat(script("bundle")).contains("deploy app-image.ref");
        assertThat(Files.readString(WORKFLOW)).doesNotContain(":latest");
        assertThat(script("inspect-release")).contains("tar -xOf");
        assertThat(script("inspect-release")).contains("$APP_IMAGE_REF");
    }

    @Test
    @DisplayName("워크플로도 Gradle test의 입력이다")
    void includesWorkflowsAsGradleTestInputs() throws Exception {
        // when
        var build = Files.readString(Path.of("build.gradle"));

        // then
        assertThat(build).contains("inputs.dir('../.github/workflows')");
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
}
