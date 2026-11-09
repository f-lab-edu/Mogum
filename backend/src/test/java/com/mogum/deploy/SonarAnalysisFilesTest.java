package com.mogum.deploy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class SonarAnalysisFilesTest {
    private static final Path WORKFLOW = Path.of("../.github/workflows/sonarcloud-analyze.yml");
    private static final String SETUP_JAVA = "actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961";
    private static final String SETUP_GRADLE = "gradle/actions/setup-gradle@3f5f9adaf7d9fecd50b5935e54106014257a94e6";
    private static final String BACKEND_TEST = "./backend/gradlew -p backend test";

    @Test
    @DisplayName("체크아웃 뒤 Java 21과 Gradle을 준비하고 프로젝트 키 설정과 스캔 전에 테스트한다")
    void preparesJavaAndGradleAndTestsBeforeProjectKeyAndScan() throws Exception {
        // when
        var steps = steps();

        // then
        assertThat(steps).hasSize(6);
        assertThat(steps.get(0).get("uses")).isEqualTo("actions/checkout@v3");
        assertThat(steps.get(1).get("uses")).isEqualTo(SETUP_JAVA);
        assertThat(steps.get(1).get("with")).isEqualTo(Map.of("distribution", "temurin", "java-version", "21"));
        assertThat(steps.get(2).get("uses")).isEqualTo(SETUP_GRADLE);
        assertThat(steps.get(3).get("run")).isEqualTo(BACKEND_TEST);
        assertThat(steps.get(4).get("name")).isEqualTo("Set SonarCloud Project Key");
        assertThat(steps.get(5).get("uses")).isEqualTo("SonarSource/sonarcloud-github-action@master");
    }

    @Test
    @DisplayName("추가한 Java와 Gradle 액션을 전체 SHA와 릴리스 주석으로 고정한다")
    void pinsAddedActionsToFullShasAndReleaseComments() throws Exception {
        // given
        var releases = Map.of(SETUP_JAVA, "v5.7.0", SETUP_GRADLE, "v6.4.0");

        // when
        var text = Files.readString(WORKFLOW);

        // then
        for (var release : releases.entrySet()) {
            assertThat(text.lines().filter(line -> line.matches(
                    "\\s*uses: " + Pattern.quote(release.getKey())
                            + " # " + Pattern.quote(release.getValue()) + "\\s*")).count())
                    .as("액션 SHA와 릴리스 주석: " + release.getKey()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("백엔드 테스트가 실패해도 Sonar 스캔을 계속한다")
    void continuesScanningAfterBackendTestFailure() throws Exception {
        // when
        var test = steps().stream().filter(step -> BACKEND_TEST.equals(step.get("run"))).findFirst().orElseThrow();

        // then
        assertThat(test).containsEntry("continue-on-error", true).doesNotContainKey("if");
    }

    @Test
    @DisplayName("백엔드 테스트와 클래스 및 XML 경로를 지정하고 기존 제외와 전체 소스 범위를 유지한다")
    void separatesBackendTestsAndImportsXmlWithoutChangingSourceScope() throws Exception {
        // when
        var properties = properties();

        // then
        assertThat(properties)
                .containsEntry("sonar.tests", "backend/src/test")
                .containsEntry("sonar.java.binaries", "backend/build/classes/java/main")
                .containsEntry("sonar.java.test.binaries", "backend/build/classes/java/test")
                .containsEntry("sonar.coverage.jacoco.xmlReportPaths", "backend/build/reports/jacoco/test/jacocoTestReport.xml")
                .containsEntry("sonar.coverage.exclusions", "frontend/**")
                .doesNotContainKey("sonar.sources");
        assertThat(properties.getProperty("sonar.exclusions").split(","))
                .containsExactlyInAnyOrder("docs/design/**", "**/*.jar",
                        "backend/src/main/resources/db/migration/**", "backend/src/test/**");
    }

    @Test
    @DisplayName("Sonar 설정도 Gradle test의 입력이다")
    void includesSonarPropertiesAsGradleTestInput() throws Exception {
        // when
        var build = Files.readString(Path.of("build.gradle"));

        // then
        assertThat(build).contains("inputs.file('../sonar-project.properties')");
    }

    private static Properties properties() throws Exception {
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("../sonar-project.properties"))) {
            properties.load(reader);
        }
        return properties;
    }

    private static List<Map<String, Object>> steps() throws Exception {
        var workflow = map(new Yaml().load(Files.readString(WORKFLOW)));
        var job = map(map(workflow.get("jobs")).get("CodeAnalyze"));
        return ((List<?>) job.get("steps")).stream().map(SonarAnalysisFilesTest::map).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
