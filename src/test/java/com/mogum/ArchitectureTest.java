package com.mogum;

import com.mogum.archfixture.api.ApiToGeneration;
import com.mogum.archfixture.api.ApiToInfra;
import com.mogum.archfixture.api.ApiToWorker;
import com.mogum.archfixture.core.CoreToApi;
import com.mogum.archfixture.core.CoreToInfra;
import com.mogum.archfixture.core.CoreToWorker;
import com.mogum.archfixture.core.SpringWebInCore;
import com.mogum.archfixture.core.job.JobToGeneration;
import com.mogum.archfixture.worker.WorkerToInfra;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchitectureTest {
    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.mogum");

    // "..mogum"은 com.mogum 자체만 허용하므로 설정 배선은 com.mogum.config 등 하위 패키지에 두지 않는다.
    private static final ArchRule GENERATION_ONLY_FROM_ALLOWED_PACKAGES = noClasses().that()
            .resideOutsideOfPackages("..core.generation..", "..worker..", "..infra..", "..mogum")
            .should().dependOnClassesThat().resideInAPackage("..core.generation..");
    private static final ArchRule API_DOES_NOT_DEPEND_ON_WORKER = noClasses().that()
            .resideInAPackage("..api..").should().dependOnClassesThat().resideInAPackage("..worker..");
    private static final ArchRule API_AND_WORKER_DO_NOT_DEPEND_ON_INFRA = noClasses().that()
            .resideInAnyPackage("..api..", "..worker..").should().dependOnClassesThat().resideInAPackage("..infra..");
    private static final ArchRule CORE_DOES_NOT_DEPEND_ON_OUTSIDE = noClasses().that()
            .resideInAPackage("..core..").should().dependOnClassesThat().resideInAnyPackage("..api..", "..worker..", "..infra..");
    private static final ArchRule CORE_DOES_NOT_DEPEND_ON_INFRA_TECHNOLOGY = noClasses().that()
            .resideInAPackage("..core..").should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.web..", "org.springframework.data..",
                    "org.springframework.transaction..",
                    "jakarta.persistence..",
                    "com.openai..");

    @Test
    void 생성은_worker만_부른다() {
        GENERATION_ONLY_FROM_ALLOWED_PACKAGES.allowEmptyShould(true).check(CLASSES);
    }

    @Test
    void api는_worker를_모른다() {
        API_DOES_NOT_DEPEND_ON_WORKER.allowEmptyShould(true).check(CLASSES);
    }

    @Test
    void api와_worker는_infra를_모른다() {
        API_AND_WORKER_DO_NOT_DEPEND_ON_INFRA.allowEmptyShould(true).check(CLASSES);
    }

    @Test
    void core는_바깥을_모른다() {
        CORE_DOES_NOT_DEPEND_ON_OUTSIDE.allowEmptyShould(true).check(CLASSES);
    }

    @Test
    void core는_인프라_기술을_모른다() {
        CORE_DOES_NOT_DEPEND_ON_INFRA_TECHNOLOGY.allowEmptyShould(true).check(CLASSES);
    }

    @ParameterizedTest
    @ValueSource(classes = {ApiToGeneration.class, JobToGeneration.class})
    void 생성_직접_참조와_core_job_우회를_거부한다(Class<?> fixture) {
        assertViolation(GENERATION_ONLY_FROM_ALLOWED_PACKAGES, fixture);
    }

    @Test
    void api에서_worker_참조를_거부한다() {
        assertViolation(API_DOES_NOT_DEPEND_ON_WORKER, ApiToWorker.class);
    }

    @ParameterizedTest
    @ValueSource(classes = {ApiToInfra.class, WorkerToInfra.class})
    void api와_worker에서_infra_참조를_거부한다(Class<?> fixture) {
        assertViolation(API_AND_WORKER_DO_NOT_DEPEND_ON_INFRA, fixture);
    }

    @ParameterizedTest
    @ValueSource(classes = {CoreToApi.class, CoreToWorker.class, CoreToInfra.class})
    void core에서_바깥_패키지_참조를_거부한다(Class<?> fixture) {
        assertViolation(CORE_DOES_NOT_DEPEND_ON_OUTSIDE, fixture);
    }

    @Test
    void core에서_Spring_Web_참조를_거부한다() {
        assertViolation(CORE_DOES_NOT_DEPEND_ON_INFRA_TECHNOLOGY, SpringWebInCore.class);
    }

    @Test
    void 운영_검사는_애플리케이션을_포함하고_테스트를_제외한다() {
        assertTrue(CLASSES.contain(MogumApplication.class));
        assertFalse(CLASSES.contain(ArchitectureTest.class));
        assertTrue(CLASSES.stream().noneMatch(type -> type.getPackageName().startsWith("com.mogum.archfixture")));
    }

    private static void assertViolation(ArchRule rule, Class<?> fixture) {
        JavaClasses classes = new ClassFileImporter().importClasses(fixture);
        AssertionError violation = assertThrows(AssertionError.class, () -> rule.check(classes));
        assertTrue(violation.getMessage().contains(fixture.getName()), violation::getMessage);
    }
}
