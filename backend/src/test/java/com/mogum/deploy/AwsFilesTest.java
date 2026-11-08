package com.mogum.deploy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AwsFilesTest {
    private static final Path AWS = Path.of("../deploy/aws");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ECR = "arn:aws:ecr:ap-northeast-2:ACCOUNT_ID:repository/mogum-app";
    private static final String BUCKET = "arn:aws:s3:::BUCKET_NAME";
    private static final String INSTANCE = "arn:aws:ec2:ap-northeast-2:ACCOUNT_ID:instance/INSTANCE_ID";
    private static final String DOCUMENT = "arn:aws:ssm:ap-northeast-2::document/AWS-RunShellScript";
    private static final Set<String> FILES = Set.of("github-oidc-trust.json", "deploy-policy.json",
            "ec2-trust.json", "instance-policy.json", "ecr-lifecycle.json", "s3-lifecycle.json");

    @Test
    @DisplayName("AWS 견본은 정해진 JSON 여섯 개뿐이다")
    void containsOnlySixAwsSamples() throws Exception {
        // when
        try (var files = Files.list(AWS)) {
            // then
            assertThat(files.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet()))
                    .isEqualTo(FILES);
        }
    }

    @ParameterizedTest(name = "파일 {0}")
    @ValueSource(strings = {"github-oidc-trust.json", "deploy-policy.json", "ec2-trust.json",
            "instance-policy.json", "ecr-lifecycle.json", "s3-lifecycle.json"})
    @DisplayName("모든 견본은 JSON 객체로 파싱된다")
    void parsesSamplesAsJsonObjects(String file) throws Exception {
        // when
        var sample = read(file);

        // then
        assertThat(sample.isObject()).isTrue();
    }

    @Test
    @DisplayName("OIDC는 계정 공급자의 main 브랜치와 STS audience만 정확히 허용한다")
    void restrictsOidcToMainBranchAndStsAudience() throws Exception {
        // when
        var sample = read("github-oidc-trust.json");

        // then
        assertThat(sample).isEqualTo(JSON.readTree("""
                {"Version":"2012-10-17","Statement":[{
                  "Effect":"Allow",
                  "Principal":{"Federated":"arn:aws:iam::ACCOUNT_ID:oidc-provider/token.actions.githubusercontent.com"},
                  "Action":"sts:AssumeRoleWithWebIdentity",
                  "Condition":{"StringEquals":{
                    "token.actions.githubusercontent.com:aud":"sts.amazonaws.com",
                    "token.actions.githubusercontent.com:sub":"repo:f-lab-edu@54677968/Mogum@1378404194:ref:refs/heads/main"
                  }}
                }]}
                """));
    }

    @Test
    @DisplayName("인스턴스 역할은 EC2 서비스만 신뢰한다")
    void trustsOnlyEc2Service() throws Exception {
        // when
        var sample = read("ec2-trust.json");

        // then
        assertThat(sample).isEqualTo(JSON.readTree("""
                {"Version":"2012-10-17","Statement":[{
                  "Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"
                }]}
                """));
    }

    @ParameterizedTest(name = "파일 {0}")
    @ValueSource(strings = {"deploy-policy.json", "instance-policy.json"})
    @DisplayName("역할은 삭제와 설정변경과 동작 와일드카드를 허용하지 않는다")
    void disallowsDeletionConfigurationChangesAndActionWildcards(String file) throws Exception {
        // when
        var policy = read(file);

        // then
        assertThat(fields(policy)).isEqualTo(Set.of("Version", "Statement"));
        assertThat(policy.path("Version").asText()).isEqualTo("2012-10-17");
        assertThat(policy.path("Statement").isArray()).isTrue();
        assertThat(policy.path("Statement")).isNotEmpty();
        for (var statement : policy.path("Statement")) {
            assertThat(Set.of("Sid", "Effect", "Action", "Resource", "Condition").containsAll(fields(statement))).isTrue();
            assertThat(statement.path("Effect").asText()).isEqualTo("Allow");
            var actions = strings(statement.path("Action"));
            var resources = strings(statement.path("Resource"));
            for (var action : actions) {
                var lower = action.toLowerCase(Locale.ROOT);
                assertThat(action.contains("*") || action.contains("?")).as(action).isFalse();
                assertThat(lower.contains("delete") || lower.contains("policy") || lower.contains("lifecycle")
                        || lower.contains("versioning")).as(action).isFalse();
                assertThat(action.startsWith("ecr:") || action.startsWith("s3:") || action.startsWith("ssm:"))
                        .as(action).isTrue();
                if (resources.contains("*")) {
                    assertThat(resources).isEqualTo(Set.of("*"));
                    assertThat(Set.of("ecr:GetAuthorizationToken", "ssm:GetCommandInvocation")).as(action).contains(action);
                }
            }
            if (statement.has("Condition")) {
                assertThat(actions).as("목록 접두사 조건만 사용한다").isEqualTo(Set.of("s3:ListBucket"));
            }
        }
    }

    @Test
    @DisplayName("배포 역할의 ECR은 인증과 해당 저장소 push와 조회뿐이다")
    void restrictsDeployEcrGrants() throws Exception {
        // when
        var policy = read("deploy-policy.json");

        // then
        assertThat(grants(policy, "ecr:")).isEqualTo(Map.of(
                "ecr:GetAuthorizationToken", Set.of("*"),
                "ecr:BatchCheckLayerAvailability", Set.of(ECR),
                "ecr:InitiateLayerUpload", Set.of(ECR),
                "ecr:UploadLayerPart", Set.of(ECR),
                "ecr:CompleteLayerUpload", Set.of(ECR),
                "ecr:PutImage", Set.of(ECR),
                "ecr:BatchGetImage", Set.of(ECR),
                "ecr:DescribeImages", Set.of(ECR)));
    }

    @Test
    @DisplayName("배포 역할의 S3는 releases 읽기와 쓰기뿐이다")
    void restrictsDeployS3Grants() throws Exception {
        // when
        var policy = read("deploy-policy.json");

        // then
        assertThat(grants(policy, "s3:")).isEqualTo(Map.of("s3:GetObject", Set.of(BUCKET + "/releases/*"),
                "s3:PutObject", Set.of(BUCKET + "/releases/*"),
                "s3:AbortMultipartUpload", Set.of(BUCKET + "/releases/*")));
    }

    @Test
    @DisplayName("배포 역할은 운영 인스턴스 하나와 지정 문서에만 명령을 보낸다")
    void restrictsCommandsToProductionInstanceAndDocument() throws Exception {
        // when
        var policy = read("deploy-policy.json");

        // then
        assertThat(grants(policy, "ssm:")).isEqualTo(Map.of("ssm:SendCommand", Set.of(INSTANCE, DOCUMENT),
                "ssm:GetCommandInvocation", Set.of("*")));
    }

    @Test
    @DisplayName("인스턴스 ECR은 인증과 pull뿐이며 push가 없다")
    void restrictsInstanceEcrToPull() throws Exception {
        // when
        var policy = read("instance-policy.json");

        // then
        assertThat(grants(policy, "ecr:")).isEqualTo(Map.of("ecr:GetAuthorizationToken", Set.of("*"),
                "ecr:BatchGetImage", Set.of(ECR),
                "ecr:GetDownloadUrlForLayer", Set.of(ECR),
                "ecr:BatchCheckLayerAvailability", Set.of(ECR)));
        assertThat(grants(policy, "ssm:")).as("SSM은 관리형 정책으로 붙인다").isEmpty();
    }

    @Test
    @DisplayName("인스턴스는 releases 읽기와 backups 읽기 쓰기 목록만 갖는다")
    void restrictsInstanceS3ToReleasesAndBackups() throws Exception {
        // when
        var policy = read("instance-policy.json");

        // then
        assertThat(grants(policy, "s3:"))
                .isEqualTo(Map.of("s3:GetObject", Set.of(BUCKET + "/releases/*", BUCKET + "/backups/*"),
                "s3:PutObject", Set.of(BUCKET + "/backups/*"),
                "s3:AbortMultipartUpload", Set.of(BUCKET + "/backups/*"),
                "s3:ListBucket", Set.of(BUCKET)));
        int listings = 0;
        for (var statement : policy.path("Statement")) {
            if (strings(statement.path("Action")).contains("s3:ListBucket")) {
                listings++;
                assertThat(statement.path("Condition")).isEqualTo(JSON.readTree("""
                        {"StringEquals":{"s3:prefix":"backups/"}}
                        """));
            }
        }
        assertThat(listings).isEqualTo(1);
    }

    @Test
    @DisplayName("S3는 접두사별 규칙 둘이며 백업 6일과 이전 1일 합은 7일 이하다")
    void limitsS3RetentionByPrefix() throws Exception {
        // when
        var rules = read("s3-lifecycle.json").path("Rules");

        // then
        assertThat(rules.isArray()).isTrue();
        assertThat(rules.size()).isEqualTo(2);
        var prefixes = new HashSet<String>();
        for (var rule : rules) {
            assertThat(fields(rule)).isEqualTo(Set.of("ID", "Status", "Filter", "Expiration", "NoncurrentVersionExpiration",
                    "AbortIncompleteMultipartUpload"));
            assertThat(rule.path("Status").asText()).isEqualTo("Enabled");
            assertThat(fields(rule.path("Filter"))).isEqualTo(Set.of("Prefix"));
            var prefix = rule.path("Filter").path("Prefix").asText();
            assertThat(prefixes.add(prefix)).isTrue();
            int currentDays = rule.path("Expiration").path("Days").asInt();
            int previousDays = rule.path("NoncurrentVersionExpiration").path("NoncurrentDays").asInt();
            assertThat(currentDays).isEqualTo(prefix.equals("backups/") ? 6 : 30);
            assertThat(previousDays).isEqualTo(1);
            if (prefix.equals("backups/")) assertThat(currentDays + previousDays <= 7).isTrue();
            // Days 만료는 이전 버전이 사라진 뒤 삭제 표시도 정리한다. 두 필드의 병기는 S3가 거부한다.
            assertThat(fields(rule.path("Expiration"))).isEqualTo(Set.of("Days"));
            assertThat(fields(rule.path("NoncurrentVersionExpiration"))).isEqualTo(Set.of("NoncurrentDays"));
            assertThat(rule.path("AbortIncompleteMultipartUpload")).isEqualTo(JSON.readTree("{\"DaysAfterInitiation\":1}"));
        }
        assertThat(prefixes).isEqualTo(Set.of("backups/", "releases/"));
    }

    @Test
    @DisplayName("ECR은 태그 유무와 무관하게 최근 이미지 30개만 남긴다")
    void keepsThirtyRecentEcrImages() throws Exception {
        // when
        var rules = read("ecr-lifecycle.json").path("rules");

        // then
        assertThat(rules.isArray()).isTrue();
        assertThat(rules.size()).isEqualTo(1);
        var rule = rules.get(0);
        assertThat(rule.path("rulePriority").asInt()).isEqualTo(1);
        assertThat(rule.path("selection")).isEqualTo(JSON.readTree("""
                {"tagStatus":"any","countType":"imageCountMoreThan","countNumber":30}
                """));
        assertThat(rule.path("action")).isEqualTo(JSON.readTree("{\"type\":\"expire\"}"));
    }

    private static JsonNode read(String file) throws Exception {
        return JSON.readTree(Files.readString(AWS.resolve(file)));
    }

    private static Set<String> fields(JsonNode node) {
        assertThat(node.isObject()).isTrue();
        return new HashSet<>(node.propertyNames());
    }

    private static Set<String> strings(JsonNode node) {
        var values = new HashSet<String>();
        if (node.isString()) values.add(node.asText());
        else {
            assertThat(node.isArray()).isTrue();
            for (var value : node) {
                assertThat(value.isString()).isTrue();
                assertThat(values.add(value.asText())).as("중복 항목").isTrue();
            }
        }
        assertThat(values).isNotEmpty();
        assertThat(values).doesNotContain("");
        return values;
    }

    private static Map<String, Set<String>> grants(JsonNode policy, String service) {
        var grants = new HashMap<String, Set<String>>();
        for (var statement : policy.path("Statement")) {
            for (var action : strings(statement.path("Action"))) {
                if (action.startsWith(service)) {
                    grants.computeIfAbsent(action, ignored -> new HashSet<>()).addAll(strings(statement.path("Resource")));
                }
            }
        }
        return grants;
    }
}
