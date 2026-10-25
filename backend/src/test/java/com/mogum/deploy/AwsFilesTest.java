package com.mogum.deploy;

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

import static org.junit.jupiter.api.Assertions.*;

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
    void AWS_견본은_정해진_JSON_여섯_개뿐이다() throws Exception {
        try (var files = Files.list(AWS)) {
            assertEquals(FILES, files.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"github-oidc-trust.json", "deploy-policy.json", "ec2-trust.json",
            "instance-policy.json", "ecr-lifecycle.json", "s3-lifecycle.json"})
    void 모든_견본은_JSON_객체로_파싱된다(String file) throws Exception {
        assertTrue(read(file).isObject());
    }

    @Test
    void OIDC는_계정_공급자의_main_브랜치와_STS_audience만_정확히_허용한다() throws Exception {
        assertEquals(JSON.readTree("""
                {"Version":"2012-10-17","Statement":[{
                  "Effect":"Allow",
                  "Principal":{"Federated":"arn:aws:iam::ACCOUNT_ID:oidc-provider/token.actions.githubusercontent.com"},
                  "Action":"sts:AssumeRoleWithWebIdentity",
                  "Condition":{"StringEquals":{
                    "token.actions.githubusercontent.com:aud":"sts.amazonaws.com",
                    "token.actions.githubusercontent.com:sub":"repo:f-lab-edu@54677968/Mogum@1378404194:ref:refs/heads/main"
                  }}
                }]}
                """), read("github-oidc-trust.json"));
    }

    @Test
    void 인스턴스_역할은_EC2_서비스만_신뢰한다() throws Exception {
        assertEquals(JSON.readTree("""
                {"Version":"2012-10-17","Statement":[{
                  "Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"
                }]}
                """), read("ec2-trust.json"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"deploy-policy.json", "instance-policy.json"})
    void 역할은_삭제와_설정변경과_동작_와일드카드를_허용하지_않는다(String file) throws Exception {
        var policy = read(file);
        assertEquals(Set.of("Version", "Statement"), fields(policy));
        assertEquals("2012-10-17", policy.path("Version").asText());
        assertTrue(policy.path("Statement").isArray());
        assertFalse(policy.path("Statement").isEmpty());
        for (var statement : policy.path("Statement")) {
            assertTrue(Set.of("Sid", "Effect", "Action", "Resource", "Condition").containsAll(fields(statement)));
            assertEquals("Allow", statement.path("Effect").asText());
            var actions = strings(statement.path("Action"));
            var resources = strings(statement.path("Resource"));
            for (var action : actions) {
                var lower = action.toLowerCase(Locale.ROOT);
                assertFalse(action.contains("*") || action.contains("?"), action);
                assertFalse(lower.contains("delete") || lower.contains("policy") || lower.contains("lifecycle")
                        || lower.contains("versioning"), action);
                assertTrue(action.startsWith("ecr:") || action.startsWith("s3:") || action.startsWith("ssm:"), action);
                if (resources.contains("*")) {
                    assertEquals(Set.of("*"), resources);
                    assertTrue(Set.of("ecr:GetAuthorizationToken", "ssm:GetCommandInvocation").contains(action), action);
                }
            }
            if (statement.has("Condition")) {
                assertEquals(Set.of("s3:ListBucket"), actions, "목록 접두사 조건만 사용한다");
            }
        }
    }

    @Test
    void 배포_역할의_ECR은_인증과_해당_저장소_push와_조회뿐이다() throws Exception {
        assertEquals(Map.of(
                "ecr:GetAuthorizationToken", Set.of("*"),
                "ecr:BatchCheckLayerAvailability", Set.of(ECR),
                "ecr:InitiateLayerUpload", Set.of(ECR),
                "ecr:UploadLayerPart", Set.of(ECR),
                "ecr:CompleteLayerUpload", Set.of(ECR),
                "ecr:PutImage", Set.of(ECR),
                "ecr:BatchGetImage", Set.of(ECR),
                "ecr:DescribeImages", Set.of(ECR)), grants("deploy-policy.json", "ecr:"));
    }

    @Test
    void 배포_역할의_S3는_releases_읽기와_쓰기뿐이다() throws Exception {
        assertEquals(Map.of("s3:GetObject", Set.of(BUCKET + "/releases/*"),
                "s3:PutObject", Set.of(BUCKET + "/releases/*"),
                "s3:AbortMultipartUpload", Set.of(BUCKET + "/releases/*")), grants("deploy-policy.json", "s3:"));
    }

    @Test
    void 배포_역할은_운영_인스턴스_하나와_지정_문서에만_명령을_보낸다() throws Exception {
        assertEquals(Map.of("ssm:SendCommand", Set.of(INSTANCE, DOCUMENT),
                "ssm:GetCommandInvocation", Set.of("*")), grants("deploy-policy.json", "ssm:"));
    }

    @Test
    void 인스턴스_ECR은_인증과_pull뿐이며_push가_없다() throws Exception {
        assertEquals(Map.of("ecr:GetAuthorizationToken", Set.of("*"),
                "ecr:BatchGetImage", Set.of(ECR),
                "ecr:GetDownloadUrlForLayer", Set.of(ECR),
                "ecr:BatchCheckLayerAvailability", Set.of(ECR)), grants("instance-policy.json", "ecr:"));
        assertTrue(grants("instance-policy.json", "ssm:").isEmpty(), "SSM은 관리형 정책으로 붙인다");
    }

    @Test
    void 인스턴스는_releases_읽기와_backups_읽기_쓰기_목록만_갖는다() throws Exception {
        assertEquals(Map.of("s3:GetObject", Set.of(BUCKET + "/releases/*", BUCKET + "/backups/*"),
                "s3:PutObject", Set.of(BUCKET + "/backups/*"),
                "s3:AbortMultipartUpload", Set.of(BUCKET + "/backups/*"),
                "s3:ListBucket", Set.of(BUCKET)), grants("instance-policy.json", "s3:"));
        int listings = 0;
        for (var statement : read("instance-policy.json").path("Statement")) {
            if (strings(statement.path("Action")).contains("s3:ListBucket")) {
                listings++;
                assertEquals(JSON.readTree("""
                        {"StringEquals":{"s3:prefix":"backups/"}}
                        """), statement.path("Condition"));
            }
        }
        assertEquals(1, listings);
    }

    @Test
    void S3는_접두사별_규칙_둘이며_백업_6일과_이전_1일_합은_7일_이하다() throws Exception {
        var rules = read("s3-lifecycle.json").path("Rules");
        assertTrue(rules.isArray());
        assertEquals(2, rules.size());
        var prefixes = new HashSet<String>();
        for (var rule : rules) {
            assertEquals(Set.of("ID", "Status", "Filter", "Expiration", "NoncurrentVersionExpiration",
                    "AbortIncompleteMultipartUpload"), fields(rule));
            assertEquals("Enabled", rule.path("Status").asText());
            assertEquals(Set.of("Prefix"), fields(rule.path("Filter")));
            var prefix = rule.path("Filter").path("Prefix").asText();
            assertTrue(prefixes.add(prefix));
            int currentDays = rule.path("Expiration").path("Days").asInt();
            int previousDays = rule.path("NoncurrentVersionExpiration").path("NoncurrentDays").asInt();
            assertEquals(prefix.equals("backups/") ? 6 : 30, currentDays);
            assertEquals(1, previousDays);
            if (prefix.equals("backups/")) assertTrue(currentDays + previousDays <= 7);
            // Days 만료는 이전 버전이 사라진 뒤 삭제 표시도 정리한다. 두 필드의 병기는 S3가 거부한다.
            assertEquals(Set.of("Days"), fields(rule.path("Expiration")));
            assertEquals(Set.of("NoncurrentDays"), fields(rule.path("NoncurrentVersionExpiration")));
            assertEquals(JSON.readTree("{\"DaysAfterInitiation\":1}"), rule.path("AbortIncompleteMultipartUpload"));
        }
        assertEquals(Set.of("backups/", "releases/"), prefixes);
    }

    @Test
    void ECR은_태그_유무와_무관하게_최근_이미지_30개만_남긴다() throws Exception {
        var rules = read("ecr-lifecycle.json").path("rules");
        assertTrue(rules.isArray());
        assertEquals(1, rules.size());
        var rule = rules.get(0);
        assertEquals(1, rule.path("rulePriority").asInt());
        assertEquals(JSON.readTree("""
                {"tagStatus":"any","countType":"imageCountMoreThan","countNumber":30}
                """), rule.path("selection"));
        assertEquals(JSON.readTree("{\"type\":\"expire\"}"), rule.path("action"));
    }

    private static JsonNode read(String file) throws Exception {
        return JSON.readTree(Files.readString(AWS.resolve(file)));
    }

    private static Set<String> fields(JsonNode node) {
        assertTrue(node.isObject());
        return new HashSet<>(node.propertyNames());
    }

    private static Set<String> strings(JsonNode node) {
        var values = new HashSet<String>();
        if (node.isString()) values.add(node.asText());
        else {
            assertTrue(node.isArray());
            for (var value : node) {
                assertTrue(value.isString());
                assertTrue(values.add(value.asText()), "중복 항목");
            }
        }
        assertFalse(values.isEmpty());
        assertFalse(values.contains(""));
        return values;
    }

    private static Map<String, Set<String>> grants(String file, String service) throws Exception {
        var grants = new HashMap<String, Set<String>>();
        for (var statement : read(file).path("Statement")) {
            for (var action : strings(statement.path("Action"))) {
                if (action.startsWith(service)) {
                    grants.computeIfAbsent(action, ignored -> new HashSet<>()).addAll(strings(statement.path("Resource")));
                }
            }
        }
        return grants;
    }
}
