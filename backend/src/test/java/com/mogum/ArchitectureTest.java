package com.mogum;

import com.mogum.archfixture.account.AccountToGeneration;
import com.mogum.archfixture.account.AccountToWorker;
import com.mogum.archfixture.generation.TransactionalGeneration;
import com.mogum.archfixture.job.JobToGeneration;
import com.mogum.archfixture.worker.JakartaTransactionalWorkerMethod;
import com.mogum.archfixture.worker.TransactionalWorkerMethod;
import com.mogum.account.controller.MeController;
import com.mogum.account.dto.MeResponse;
import com.mogum.common.ApiErrorController;
import com.mogum.common.ApiResponse;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
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
    private static final ArchRule CONTROLLER_RESPONSE_TYPES = methods().that(new DescribedPredicate<JavaMethod>(
            "are request mappings in REST controllers except ApiErrorController") {
        @Override
        public boolean test(JavaMethod method) {
            return method.getOwner().isAnnotatedWith(RestController.class)
                    && !method.getOwner().isEquivalentTo(ApiErrorController.class)
                    && (method.isAnnotatedWith(RequestMapping.class) || method.isMetaAnnotatedWith(RequestMapping.class));
        }
    }).should(new ArchCondition<JavaMethod>(
            "return ApiResponse<?>, ResponseEntity<ApiResponse<?>>, ResponseEntity<Void>, or void") {
        @Override
        public void check(JavaMethod method, ConditionEvents events) {
            events.add(new SimpleConditionEvent(method, isAllowedResponseType(method.getReturnType()),
                    method.getFullName() + " returns " + method.getReturnType().getName()));
        }
    });

    private static boolean isAllowedResponseType(JavaType type) {
        if (type.toErasure().isEquivalentTo(void.class)) {
            return true;
        }
        if (isApiResponse(type)) {
            return true;
        }
        if (type instanceof JavaParameterizedType parameterized
                && type.toErasure().isEquivalentTo(ResponseEntity.class)) {
            JavaType body = parameterized.getActualTypeArguments().getFirst();
            return body.getName().equals(Void.class.getName()) || isApiResponse(body);
        }
        return false;
    }

    private static boolean isApiResponse(JavaType type) {
        return type instanceof JavaParameterizedType && type.toErasure().isEquivalentTo(ApiResponse.class);
    }

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

    @Test
    void REST_컨트롤러의_성공_응답은_명시적으로_감싼다() {
        assertTrue(CLASSES.contain(MeController.class));
        assertTrue(CLASSES.contain(ApiErrorController.class));
        CONTROLLER_RESPONSE_TYPES.check(CLASSES);
    }

    @ParameterizedTest
    @ValueSource(classes = {UnwrappedController.class, UnwrappedEntityController.class,
            RawEntityController.class, RawResponseController.class})
    void DTO_직접_반환과_감싸지_않은_ResponseEntity와_원시_타입을_거부한다(Class<?> fixture) {
        assertViolation(CONTROLLER_RESPONSE_TYPES, fixture);
    }

    @Test
    void 성공_응답의_네_반환_모양을_허용하고_매핑_없는_메서드는_검사하지_않는다() {
        CONTROLLER_RESPONSE_TYPES.check(new ClassFileImporter().importClasses(AllowedController.class));
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
        assertFalse(CLASSES.contain(AllowedController.class));
        assertTrue(CLASSES.stream().noneMatch(type -> type.getPackageName().startsWith("com.mogum.archfixture")));
    }

    private static void assertViolation(ArchRule rule, Class<?> fixture) {
        JavaClasses classes = new ClassFileImporter().importClasses(fixture);
        AssertionError violation = assertThrows(AssertionError.class, () -> rule.check(classes));
        assertTrue(violation.getMessage().contains(fixture.getName()), violation::getMessage);
    }

    @RestController
    static class UnwrappedController {
        @RequestMapping("/architecture/unwrapped")
        public MeResponse response() { return null; }
    }

    @RestController
    static class UnwrappedEntityController {
        @PostMapping("/architecture/unwrapped-entity")
        public ResponseEntity<MeResponse> response() { return null; }
    }

    @RestController
    static class RawEntityController {
        @GetMapping("/architecture/raw-entity")
        public ResponseEntity response() { return null; }
    }

    @RestController
    static class RawResponseController {
        @GetMapping("/architecture/raw-response")
        public ApiResponse response() { return null; }
    }

    @RestController
    static class AllowedController {
        @GetMapping("/architecture/wrapped")
        public ApiResponse<MeResponse> response() { return null; }

        @PostMapping("/architecture/accepted")
        public ResponseEntity<ApiResponse<MeResponse>> accepted() { return null; }

        @DeleteMapping("/architecture/empty-entity")
        public ResponseEntity<Void> emptyEntity() { return ResponseEntity.noContent().build(); }

        @PatchMapping("/architecture/empty")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public void empty() { }

        public MeResponse helper() { return null; }
    }
}
