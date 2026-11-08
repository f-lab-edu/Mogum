package com.mogum.common.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.MethodParameter;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.json.JsonContent;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.ServletWebRequest;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {
    @Test
    @DisplayName("길이 위반만이면 넘친 칸과 reason을 싣는다")
    void returnsFieldReasonForLengthViolation() throws Exception {
        // given
        var exception = validationException(validate(new ValidationRequest("private-topic", "학습")));
        var request = webRequest();

        // when
        var response = new ApiExceptionHandler().handleException(exception, request);

        // then
        assertValidationResponse(response, """
                {"code":"FIELD_INVALID","message":"입력을 확인해 주십시오",
                 "fields":[{"field":"topic","reason":"TOO_LONG"}]}
                """);
    }

    @Test
    @DisplayName("필수값 위반과 길이 위반을 함께 싣는다")
    void returnsRequiredAndLengthViolationsTogether() throws Exception {
        // given
        var exception = validationException(validate(new ValidationRequest("private-topic", null)));
        var request = webRequest();

        // when
        var response = new ApiExceptionHandler().handleException(exception, request);

        // then
        assertValidationResponse(response, """
                {"code":"FIELD_INVALID","message":"입력을 확인해 주십시오",
                 "fields":[{"field":"purpose","reason":"REQUIRED"},{"field":"topic","reason":"TOO_LONG"}]}
                """);
    }

    @Test
    @DisplayName("제약 선언을 확인할 수 없는 Size는 fields를 생략한다")
    void omitsFieldsWhenConstraintDeclarationIsUnavailable() throws Exception {
        // given
        var error = new FieldError("request", "topic", "private rejected input", false,
                new String[]{"Size"}, null, "private constraint message");
        var exception = validationException(List.of(error));
        var request = webRequest();

        // when
        var response = new ApiExceptionHandler().handleException(exception, request);

        // then
        assertValidationResponse(response, """
                {"code":"INVALID_REQUEST","message":"요청을 확인할 수 없습니다"}
                """);
    }

    @Test
    @DisplayName("객체 위반이 섞이면 fields를 생략한다")
    void omitsFieldsWhenObjectViolationIsPresent() throws Exception {
        // given
        var errors = new java.util.ArrayList<>(validate(new ValidationRequest("private-topic", "학습")));
        errors.add(new ObjectError("request", new String[]{"Consistency"}, null, "private constraint message"));
        var exception = validationException(errors);
        var request = webRequest();

        // when
        var response = new ApiExceptionHandler().handleException(exception, request);

        // then
        assertValidationResponse(response, """
                {"code":"INVALID_REQUEST","message":"요청을 확인할 수 없습니다"}
                """);
    }

    @Test
    @DisplayName("Size는 컬렉션과 맵과 배열의 상한 위반을 칸별로 반환한다")
    void returnsLengthViolationsForCollectionsMapsAndArrays() throws Exception {
        // given
        var exception = validationException(validate(new SizedValues(List.of("private", "input"),
                Map.of("private", "value", "input", "value"), new String[]{"private", "input"})));
        var request = webRequest();

        // when
        var response = new ApiExceptionHandler().handleException(exception, request);

        // then
        assertValidationResponse(response, """
                {"code":"FIELD_INVALID","message":"입력을 확인해 주십시오","fields":[
                 {"field":"array","reason":"TOO_LONG"},{"field":"list","reason":"TOO_LONG"},
                 {"field":"map","reason":"TOO_LONG"}]}
                """);
    }

    @Test
    @DisplayName("Size의 컬렉션과 맵과 배열 하한 위반은 일반 입력 오류로 반환한다")
    void returnsInvalidRequestForCollectionsMapsAndArraysBelowMinimum() throws Exception {
        // given
        var exception = validationException(validate(new SizedValues(List.of(), Map.of(), new String[]{})));
        var request = webRequest();

        // when
        var response = new ApiExceptionHandler().handleException(exception, request);

        // then
        assertValidationResponse(response, """
                {"code":"INVALID_REQUEST","message":"요청을 확인할 수 없습니다"}
                """);
    }

    @Test
    @DisplayName("없거나 남의 리소스는 공통 404로 변환한다")
    void convertsMissingOrForeignResourceToNotFound() {
        // given
        var exception = new ApiException(ErrorCode.NOT_FOUND);

        // when
        var response = new ApiExceptionHandler().apiException(exception);

        // then
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
        assertThat(response.getBody().message()).isEqualTo("찾을 수 없습니다");
        assertThat(response.getHeaders().getFirst("Retry-After")).isNull();
    }

    @Test
    @DisplayName("도메인 오류는 재시도 초를 본문과 헤더에 같이 싣는다")
    void includesRetryDelayInBodyAndHeader() {
        // given
        var mvc = mvc(new ApiException(ErrorCode.NOT_FOUND, 37));

        // when
        var result = mvc.get().uri("/test/error").exchange();

        // then
        assertThat(result).hasStatus(404);
        assertThat(result).headers().hasValue("Retry-After", "37");
        assertThat(result).bodyJson().isStrictlyEqualTo("""
                {"code":"NOT_FOUND","message":"찾을 수 없습니다","retryAfterSeconds":37}
                """);
    }

    @Test
    @DisplayName("도메인 오류에 재시도가 없으면 본문 키와 헤더를 생략한다")
    void omitsRetryDelayFromBodyAndHeader() {
        // given
        var mvc = mvc(new ApiException(ErrorCode.NOT_FOUND));

        // when
        var result = mvc.get().uri("/test/error").exchange();

        // then
        assertThat(result).hasStatus(404);
        assertThat(result).headers().doesNotContainHeader("Retry-After");
        assertThat(result).bodyJson().isStrictlyEqualTo("""
                {"code":"NOT_FOUND","message":"찾을 수 없습니다"}
                """);
    }

    @Test
    @DisplayName("MVC의 지원하지 않는 본문 형식은 415를 유지한다")
    void preservesUnsupportedMediaTypeStatus() throws Exception {
        // given
        var exception = new HttpMediaTypeNotSupportedException("private content type");
        var request = webRequest();

        // when
        var response = new ApiExceptionHandler().handleException(exception, request);

        // then
        assertThat(response.getStatusCode().value()).isEqualTo(415);
        assertThat(response.getBody()).isEqualTo(new ApiError("INVALID_REQUEST", "요청을 확인할 수 없습니다", null, null));
    }

    @Test
    @DisplayName("MVC의 메서드 오류는 상태와 Allow 헤더를 보존한다")
    void preservesMethodErrorStatusAndAllowHeader() throws Exception {
        // given
        var exception = new HttpRequestMethodNotSupportedException("POST", List.of("GET"));
        var request = webRequest();

        // when
        var response = new ApiExceptionHandler().handleException(exception, request);

        // then
        assertThat(response.getStatusCode().value()).isEqualTo(405);
        assertThat(response.getHeaders().getFirst("Allow")).isEqualTo("GET");
        assertThat(response.getBody()).isEqualTo(new ApiError("METHOD_NOT_ALLOWED", "지원하지 않는 요청 방식입니다", null, null));
    }

    @Test
    @DisplayName("잘못된 JSON은 상세 원문 없이 공통 400으로 변환한다")
    void convertsMalformedJsonWithoutPrivateDetails() throws Exception {
        // given
        var exception = new HttpMessageNotReadableException("private JSON input", new MockHttpInputMessage(new byte[0]));
        var request = webRequest();

        // when
        var response = new ApiExceptionHandler().handleException(exception, request);

        // then
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isEqualTo(new ApiError("INVALID_REQUEST", "요청을 확인할 수 없습니다", null, null));
    }

    @Test
    @DisplayName("처리되지 않은 컨트롤러 예외는 상세 원문 없이 공통 JSON 500이다")
    void convertsUnhandledControllerExceptionWithoutPrivateDetails() {
        // given
        var mvc = mvc(new IllegalStateException("private controller failure"));

        // when
        var result = mvc.get().uri("/test/error").exchange();

        // then
        assertThat(result).hasStatus(500);
        assertThat(result).bodyJson().isStrictlyEqualTo("""
                {"code":"INTERNAL_ERROR","message":"요청을 처리할 수 없습니다"}
                """);
    }

    private MockMvcTester mvc(RuntimeException failure) {
        return MockMvcTester.of(List.of(new Controllers.FailingController(failure)), builder -> builder
                .setControllerAdvice(new ApiExceptionHandler()).build());
    }

    // standalone 전용 컨트롤러가 다른 슬라이스나 통합 컨텍스트의 스캔에 섞이지 않게 한다.
    @TestConfiguration(proxyBeanMethods = false)
    static class Controllers {
        @RestController
        static class FailingController {
            private final RuntimeException failure;

            FailingController(RuntimeException failure) {
                this.failure = failure;
            }

            @GetMapping("/test/error")
            public void fail() {
                throw failure;
            }
        }
    }

    private static ServletWebRequest webRequest() {
        return new ServletWebRequest(new MockHttpServletRequest(), new MockHttpServletResponse());
    }

    private static List<ObjectError> validate(Object target) {
        var binding = new BeanPropertyBindingResult(target, "request");
        try (var validator = new LocalValidatorFactoryBean()) {
            validator.afterPropertiesSet();
            validator.validate(target, binding);
        }
        return binding.getAllErrors();
    }

    private static MethodArgumentNotValidException validationException(List<ObjectError> errors) throws Exception {
        var binding = new BeanPropertyBindingResult(new Object(), "request");
        errors.forEach(binding::addError);
        var parameter = new MethodParameter(ApiExceptionHandlerTest.class
                .getDeclaredMethod("validationTarget", Object.class), 0);
        return new MethodArgumentNotValidException(parameter, binding);
    }

    private static void assertValidationResponse(ResponseEntity<Object> response, String expected) {
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        var json = JsonMapper.builder().build().writeValueAsString(response.getBody());
        assertThat(new JsonContent(json)).isStrictlyEqualTo(expected);
        assertThat(json).doesNotContain("private");
    }

    private void validationTarget(Object request) { }

    record ValidationRequest(@NotBlank @Size(max = 5) String topic, @NotNull String purpose) { }

    static final class SizedValues {
        @Size(min = 1, max = 1)
        private final List<String> list;
        @Size(min = 1, max = 1)
        private final Map<String, String> map;
        @Size(min = 1, max = 1)
        private final String[] array;

        SizedValues(List<String> list, Map<String, String> map, String[] array) {
            this.list = list;
            this.map = map;
            this.array = array;
        }
    }
}
