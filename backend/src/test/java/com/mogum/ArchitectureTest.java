package com.mogum;

import com.mogum.archfixture.account.AccountToGeneration;
import com.mogum.archfixture.account.AccountToWorker;
import com.mogum.archfixture.generation.TransactionalGeneration;
import com.mogum.archfixture.job.JobToGeneration;
import com.mogum.archfixture.worker.JakartaTransactionalWorkerMethod;
import com.mogum.archfixture.worker.TransactionalWorkerMethod;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchitectureTest {
    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.mogum");
    private static final String SPRING_TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional";
    private static final String JAKARTA_TRANSACTIONAL = "jakarta.transaction.Transactional";

    // config 는 app.role 별 빈 등록에서 worker 와 generation 을 조립한다.
    private static final ArchRule GENERATION_ONLY_FROM_WORKER = noClasses().that()
            .resideOutsideOfPackages("..generation..", "..worker..", "..config..")
            .should().dependOnClassesThat().resideInAPackage("..generation..");
    private static final ArchRule WORKER_ONLY_FROM_CONFIG = noClasses().that()
            .resideOutsideOfPackages("..worker..", "..config..")
            .should().dependOnClassesThat().resideInAPackage("..worker..");
    private static final ArchRule NO_TRANSACTIONAL_CLASSES_AROUND_LLM_CALLS = noClasses().that()
            .resideInAnyPackage("..generation..", "..worker..")
            .should().beAnnotatedWith(SPRING_TRANSACTIONAL).orShould().beAnnotatedWith(JAKARTA_TRANSACTIONAL);
    private static final ArchRule NO_TRANSACTIONAL_METHODS_AROUND_LLM_CALLS = noMethods().that()
            .areDeclaredInClassesThat().resideInAnyPackage("..generation..", "..worker..")
            .should().beAnnotatedWith(SPRING_TRANSACTIONAL).orShould().beAnnotatedWith(JAKARTA_TRANSACTIONAL);
    private static final ArchRule OPENAI_SDK_ONLY_IN_LLM_ADAPTER = noClasses().that()
            .resideOutsideOfPackage("..generation.llm..")
            .should().dependOnClassesThat().resideInAPackage("com.openai..");

    @Test
    void 생성은_worker만_부른다() {
        GENERATION_ONLY_FROM_WORKER.allowEmptyShould(true).check(CLASSES);
    }

    @Test
    void worker를_부르는_곳은_config뿐이다() {
        WORKER_ONLY_FROM_CONFIG.allowEmptyShould(true).check(CLASSES);
    }

    @Test
    void LLM을_부르는_패키지는_Transactional을_쓰지_않는다() {
        NO_TRANSACTIONAL_CLASSES_AROUND_LLM_CALLS.allowEmptyShould(true).check(CLASSES);
        NO_TRANSACTIONAL_METHODS_AROUND_LLM_CALLS.allowEmptyShould(true).check(CLASSES);
    }

    @Test
    void OpenAI_SDK는_LLM_어댑터에만_둔다() {
        OPENAI_SDK_ONLY_IN_LLM_ADAPTER.allowEmptyShould(true).check(CLASSES);
    }

    @ParameterizedTest
    @ValueSource(classes = {AccountToGeneration.class, JobToGeneration.class})
    void 생성_직접_참조와_job_우회를_거부한다(Class<?> fixture) {
        assertViolation(GENERATION_ONLY_FROM_WORKER, fixture);
    }

    @Test
    void 도메인에서_worker_참조를_거부한다() {
        assertViolation(WORKER_ONLY_FROM_CONFIG, AccountToWorker.class);
    }

    @Test
    void 생성_클래스의_Transactional을_거부한다() {
        assertViolation(NO_TRANSACTIONAL_CLASSES_AROUND_LLM_CALLS, TransactionalGeneration.class);
    }

    @ParameterizedTest
    @ValueSource(classes = {TransactionalWorkerMethod.class, JakartaTransactionalWorkerMethod.class})
    void worker_메서드의_Transactional을_거부한다(Class<?> fixture) {
        assertViolation(NO_TRANSACTIONAL_METHODS_AROUND_LLM_CALLS, fixture);
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
