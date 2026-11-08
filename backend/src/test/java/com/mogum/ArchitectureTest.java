package com.mogum;

import com.mogum.archfixture.testrules.AccessibleObjectUsage;
import com.mogum.archfixture.testrules.JUnitAssertionsUsage;
import com.mogum.archfixture.testrules.LegacyIT;
import com.mogum.archfixture.testrules.ReflectionUtilsUsage;
import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.FakeUserRepository;
import com.tngtech.archunit.core.domain.JavaClass;
import com.mogum.archfixture.account.AccountToGeneration;
import com.mogum.archfixture.account.AccountToWorker;
import com.mogum.archfixture.generation.TransactionalGeneration;
import com.mogum.archfixture.job.JobToGeneration;
import com.mogum.archfixture.worker.JakartaTransactionalWorkerMethod;
import com.mogum.archfixture.worker.TransactionalWorkerMethod;
import com.mogum.account.controller.MeController;
import com.mogum.account.domain.User;
import com.mogum.account.dto.MeResponse;
import com.mogum.common.persistence.AuditedEntity;
import entityfixture.UnauditedEntity;
import repositoryfixture.BroadUserRepository;
import org.junit.jupiter.api.DisplayName;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.Entity;
import com.mogum.common.api.ApiErrorController;
import com.mogum.common.api.ApiResponse;
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
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchitectureTest {
    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.mogum");
    private static final JavaClasses TEST_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.ONLY_INCLUDE_TESTS)
            .withImportOption(location -> !location.contains("/com/mogum/archfixture/"))
            .importClasspath();
    private static final ArchRule TESTS_USE_ASSERTJ = noClasses().should().dependOnClassesThat()
            .haveFullyQualifiedName("org.junit.jupiter.api.Assertions");
    private static final ArchRule TEST_NAMES_AVOID_IT = noClasses().should().haveSimpleNameEndingWith("IT");
    private static final ArchRule REFLECTION_ONLY_IN_FIXTURES_AND_FAKES = classes().that(
            new DescribedPredicate<JavaClass>("are neither *Fixtures nor Fake*") {
                @Override
                public boolean test(JavaClass type) {
                    return !type.getSimpleName().endsWith("Fixtures") && !type.getSimpleName().startsWith("Fake");
                }
            }).should(new ArchCondition<JavaClass>("avoid ReflectionTestUtils and setAccessible calls") {
                @Override
                public void check(JavaClass type, ConditionEvents events) {
                    for (var dependency : type.getDirectDependenciesFromSelf()) {
                        if (dependency.getTargetClass().getName().equals("org.springframework.test.util.ReflectionTestUtils")) {
                            events.add(SimpleConditionEvent.violated(type, dependency.getDescription()));
                        }
                    }
                    for (var call : type.getMethodCallsFromSelf()) {
                        if (call.getTarget().getName().equals("setAccessible")
                                && call.getTargetOwner().isAssignableTo(java.lang.reflect.AccessibleObject.class)) {
                            events.add(SimpleConditionEvent.violated(type, call.getDescription()));
                        }
                    }
                }
            });

    @Test
    @DisplayName("테스트 코드는 JUnit Assertions 대신 AssertJ로 단언한다")
    void requiresAssertJInTests() {
        // when
        var result = TESTS_USE_ASSERTJ.evaluate(TEST_CLASSES);

        // then
        assertThat(result.hasViolation()).as(result.getFailureReport().toString()).isFalse();
    }

    @Test
    @DisplayName("JUnit Assertions에 의존하는 위반 픽스처를 거부한다")
    void rejectsJUnitAssertions() {
        // when & then
        assertViolation(TESTS_USE_ASSERTJ, JUnitAssertionsUsage.class);
    }

    @Test
    @DisplayName("리플렉션은 픽스처와 Fake 안에서만 사용한다")
    void restrictsReflectionToFixturesAndFakes() {
        // when
        var result = REFLECTION_ONLY_IN_FIXTURES_AND_FAKES.evaluate(TEST_CLASSES);

        // then
        assertThat(result.hasViolation()).as(result.getFailureReport().toString()).isFalse();
    }

    @ParameterizedTest(name = "위반 클래스 {0}")
    @ValueSource(classes = {ReflectionUtilsUsage.class, AccessibleObjectUsage.class})
    @DisplayName("일반 테스트 도우미의 ReflectionTestUtils와 setAccessible을 거부한다")
    void rejectsReflectionOutsideFixturesAndFakes(Class<?> fixture) {
        // when & then
        assertViolation(REFLECTION_ONLY_IN_FIXTURES_AND_FAKES, fixture);
    }

    @Test
    @DisplayName("테스트 클래스 이름은 IT 약어로 끝나지 않는다")
    void prohibitsItSuffixInTestNames() {
        // when
        var result = TEST_NAMES_AVOID_IT.evaluate(TEST_CLASSES);

        // then
        assertThat(result.hasViolation()).as(result.getFailureReport().toString()).isFalse();
    }

    @Test
    @DisplayName("IT 약어로 끝나는 위반 픽스처를 거부한다")
    void rejectsItSuffix() {
        // when & then
        assertViolation(TEST_NAMES_AVOID_IT, LegacyIT.class);
    }

    @Test
    @DisplayName("테스트 검사에는 테스트와 지원 도구를 포함하고 제품 코드와 위반 픽스처는 제외한다")
    void importsOnlyTestsWithoutViolationFixtures() {
        // when
        var classes = TEST_CLASSES;

        // then
        assertThat(classes.contain(ArchitectureTest.class)).isTrue();
        assertThat(classes.contain(UserFixtures.class)).isTrue();
        assertThat(classes.contain(FakeUserRepository.class)).isTrue();
        assertThat(classes.contain(com.mogum.support.TestSessions.class)).isTrue();
        assertThat(classes.contain(MogumApplication.class)).isFalse();
        assertThat(classes.stream()).noneMatch(type -> type.getPackageName().startsWith("com.mogum.archfixture"));
    }

    private static final String SPRING_TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional";
    private static final String JAKARTA_TRANSACTIONAL = "jakarta.transaction.Transactional";
    private static final ArchRule ENTITIES_EXTEND_AUDITED_ENTITY = classes().that()
            .areAnnotatedWith(Entity.class).should().beAssignableTo(AuditedEntity.class);

    private static final ArchRule REPOSITORIES_DECLARE_DOMAIN_OPERATIONS = noClasses().that()
            .areAssignableTo(Repository.class)
            .should().beAssignableTo(JpaRepository.class)
            .orShould().beAssignableTo(CrudRepository.class)
            .orShould().beAssignableTo(ListCrudRepository.class)
            .orShould().beAssignableTo(PagingAndSortingRepository.class);

    @Test
    @DisplayName("제품 레포지토리는 정리와 집계를 제공하는 상위 인터페이스를 상속하지 않는다")
    void repositoriesDeclareOnlyDomainOperations() {
        // when
        var result = REPOSITORIES_DECLARE_DOMAIN_OPERATIONS.evaluate(CLASSES);

        // then
        assertThat(result.hasViolation()).as(result.getFailureReport().toString()).isFalse();
    }

    @Test
    @DisplayName("넓은 Spring Data 인터페이스를 상속한 레포지토리를 거부한다")
    void rejectsBroadRepositoryInheritance() {
        // when & then
        assertViolation(REPOSITORIES_DECLARE_DOMAIN_OPERATIONS, BroadUserRepository.class);
    }

    @Test
    @DisplayName("모든 엔티티는 공통 시각 상위 클래스를 상속한다")
    void requiresAuditedEntitySuperclass() {
        // when
        var result = ENTITIES_EXTEND_AUDITED_ENTITY.evaluate(CLASSES);

        // then
        assertThat(CLASSES.contain(User.class)).isTrue();
        assertThat(result.hasViolation()).as(result.getFailureReport().toString()).isFalse();
    }

    @Test
    @DisplayName("공통 시각 상위 클래스를 상속하지 않은 엔티티를 거부한다")
    void rejectsUnauditedEntity() {
        // when & then
        assertViolation(ENTITIES_EXTEND_AUDITED_ENTITY, UnauditedEntity.class);
    }

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
    private static final DescribedPredicate<JavaMethod> REST_REQUEST_MAPPINGS = new DescribedPredicate<JavaMethod>(
            "are request mappings in REST controllers except ApiErrorController") {
        @Override
        public boolean test(JavaMethod method) {
            return method.getOwner().isAnnotatedWith(RestController.class)
                    && !method.getOwner().isEquivalentTo(ApiErrorController.class)
                    && (method.isAnnotatedWith(RequestMapping.class) || method.isMetaAnnotatedWith(RequestMapping.class));
        }
    };
    private static final ArchRule CONTROLLER_RESPONSE_TYPES = methods().that(REST_REQUEST_MAPPINGS)
            .should(new ArchCondition<JavaMethod>(
            "return ApiResponse<?>, ResponseEntity<ApiResponse<?>>, ResponseEntity<Void>, or void") {
        @Override
        public void check(JavaMethod method, ConditionEvents events) {
            events.add(new SimpleConditionEvent(method, isAllowedResponseType(method.getReturnType()),
                    method.getFullName() + " returns " + method.getReturnType().getName()));
        }
    });
    private static final ArchRule REQUEST_BODY_REQUIRES_VALID = methods().that(REST_REQUEST_MAPPINGS)
            .should(new ArchCondition<JavaMethod>("annotate each @RequestBody parameter with @Valid") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    for (var parameter : method.getParameters()) {
                        if (parameter.isAnnotatedWith(RequestBody.class)) {
                            events.add(new SimpleConditionEvent(parameter, parameter.isAnnotatedWith(Valid.class),
                                    method.getFullName() + " has @RequestBody without @Valid at parameter "
                                            + parameter.getIndex()));
                        }
                    }
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
    @DisplayName("생성은 worker만 부른다")
    void restrictsGenerationAccess() {
        // when
        var result = GENERATION_ONLY_FROM_WORKER.allowEmptyShould(true).evaluate(CLASSES);

        // then
        assertThat(result.hasViolation()).as(result.getFailureReport().toString()).isFalse();
    }

    @Test
    @DisplayName("worker를 부르는 곳은 config뿐이다")
    void restrictsWorkerAccess() {
        // when
        var result = WORKER_ONLY_FROM_CONFIG.allowEmptyShould(true).evaluate(CLASSES);

        // then
        assertThat(result.hasViolation()).as(result.getFailureReport().toString()).isFalse();
    }

    @Test
    @DisplayName("LLM을 부르는 패키지는 Transactional을 쓰지 않는다")
    void prohibitsTransactionsAroundLlmCalls() {
        // when
        var result = NO_TRANSACTIONAL_CLASSES_AROUND_LLM_CALLS.allowEmptyShould(true).evaluate(CLASSES);

        // then
        assertThat(result.hasViolation()).as(result.getFailureReport().toString()).isFalse();
    }

    @Test
    @DisplayName("LLM을 부르는 메서드는 Transactional을 쓰지 않는다")
    void prohibitsTransactionalMethodsAroundLlmCalls() {
        // when
        var result = NO_TRANSACTIONAL_METHODS_AROUND_LLM_CALLS.allowEmptyShould(true).evaluate(CLASSES);

        // then
        assertThat(result.hasViolation()).as(result.getFailureReport().toString()).isFalse();
    }

    @Test
    @DisplayName("OpenAI SDK는 LLM 어댑터에만 둔다")
    void restrictsOpenAiSdkToAdapter() {
        // when
        var result = OPENAI_SDK_ONLY_IN_LLM_ADAPTER.allowEmptyShould(true).evaluate(CLASSES);

        // then
        assertThat(result.hasViolation()).as(result.getFailureReport().toString()).isFalse();
    }

    @Test
    @DisplayName("REST 컨트롤러의 성공 응답은 명시적으로 감싼다")
    void requiresExplicitControllerResponseWrapping() {
        // when
        var result = CONTROLLER_RESPONSE_TYPES.evaluate(CLASSES);

        // then
        assertThat(CLASSES.contain(MeController.class)).isTrue();
        assertThat(CLASSES.contain(ApiErrorController.class)).isTrue();
        assertThat(result.hasViolation()).as(result.getFailureReport().toString()).isFalse();
    }

    @Test
    @DisplayName("REST 요청 본문은 Valid로 검증한다")
    void requiresValidationOnRequestBodies() {
        // when
        var result = REQUEST_BODY_REQUIRES_VALID.allowEmptyShould(true).evaluate(CLASSES);

        // then
        assertThat(result.hasViolation()).as(result.getFailureReport().toString()).isFalse();
    }

    @Test
    @DisplayName("Valid 없는 요청 본문을 거부한다")
    void rejectsUnvalidatedRequestBody() {
        // when & then
        assertViolation(REQUEST_BODY_REQUIRES_VALID, UnvalidatedBodyController.class);
    }

    @Test
    @DisplayName("Valid 요청 본문을 허용하고 매핑 없는 메서드와 다른 매개변수는 검사하지 않는다")
    void acceptsValidatedBodyAndIgnoresOtherParameters() {
        // given
        var classes = new ClassFileImporter().importClasses(ValidatedBodyController.class);

        // when
        var result = REQUEST_BODY_REQUIRES_VALID.evaluate(classes);

        // then
        assertThat(result.hasViolation()).as(result.getFailureReport().toString()).isFalse();
    }

    @ParameterizedTest(name = "조건 {0}")
    @ValueSource(classes = {UnwrappedController.class, UnwrappedEntityController.class,
            RawEntityController.class, RawResponseController.class})
    @DisplayName("DTO 직접 반환과 감싸지 않은 ResponseEntity와 원시 타입을 거부한다")
    void rejectsUnwrappedAndRawResponseTypes(Class<?> fixture) {
        // when & then
        assertViolation(CONTROLLER_RESPONSE_TYPES, fixture);
    }

    @Test
    @DisplayName("성공 응답의 네 반환 모양을 허용하고 매핑 없는 메서드는 검사하지 않는다")
    void acceptsSupportedResponseTypesAndIgnoresHelpers() {
        // given
        var classes = new ClassFileImporter().importClasses(AllowedController.class);

        // when
        var result = CONTROLLER_RESPONSE_TYPES.evaluate(classes);

        // then
        assertThat(result.hasViolation()).as(result.getFailureReport().toString()).isFalse();
    }

    @ParameterizedTest(name = "조건 {0}")
    @ValueSource(classes = {AccountToGeneration.class, JobToGeneration.class})
    @DisplayName("생성 직접 참조와 job 우회를 거부한다")
    void rejectsDirectAndIndirectGenerationAccess(Class<?> fixture) {
        // when & then
        assertViolation(GENERATION_ONLY_FROM_WORKER, fixture);
    }

    @Test
    @DisplayName("도메인에서 worker 참조를 거부한다")
    void rejectsDomainAccessToWorker() {
        // when & then
        assertViolation(WORKER_ONLY_FROM_CONFIG, AccountToWorker.class);
    }

    @Test
    @DisplayName("생성 클래스의 Transactional을 거부한다")
    void rejectsTransactionalGenerationClass() {
        // when & then
        assertViolation(NO_TRANSACTIONAL_CLASSES_AROUND_LLM_CALLS, TransactionalGeneration.class);
    }

    @ParameterizedTest(name = "조건 {0}")
    @ValueSource(classes = {TransactionalWorkerMethod.class, JakartaTransactionalWorkerMethod.class})
    @DisplayName("worker 메서드의 Transactional을 거부한다")
    void rejectsTransactionalWorkerMethod(Class<?> fixture) {
        // when & then
        assertViolation(NO_TRANSACTIONAL_METHODS_AROUND_LLM_CALLS, fixture);
    }

    @Test
    @DisplayName("운영 검사는 애플리케이션을 포함하고 테스트를 제외한다")
    void excludesTestsFromProductionClasses() {
        // when
        var classes = CLASSES;

        // then
        assertThat(classes.contain(MogumApplication.class)).isTrue();
        assertThat(classes.contain(ArchitectureTest.class)).isFalse();
        assertThat(classes.contain(AllowedController.class)).isFalse();
        assertThat(classes.contain(UnvalidatedBodyController.class)).isFalse();
        assertThat(classes.contain(ValidatedBodyController.class)).isFalse();
        assertThat(classes.stream().noneMatch(type -> type.getPackageName().startsWith("com.mogum.archfixture"))).isTrue();
    }

    private static void assertViolation(ArchRule rule, Class<?> fixture) {
        JavaClasses classes = new ClassFileImporter().importClasses(fixture);
        assertThatThrownBy(() -> rule.check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(fixture.getName());
    }

    @RestController
    static class UnvalidatedBodyController {
        @PostMapping("/architecture/unvalidated")
        public ApiResponse<MeResponse> response(String other, @RequestBody MeResponse body) { return null; }
    }

    @RestController
    static class ValidatedBodyController {
        @PostMapping("/architecture/validated")
        public ApiResponse<MeResponse> response(String other, @Valid @RequestBody MeResponse body) { return null; }

        public void helper(@RequestBody MeResponse body) { }
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
