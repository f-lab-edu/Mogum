package com.mogum.common.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.json.JsonContent;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static com.mogum.common.api.FieldViolation.Reason.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiErrorResponsesTest {
    @Test
    @DisplayName("칸별 위반은 중복 없이 칸과 reason 이름순으로 직렬화한다")
    void serializesDistinctViolationsInFieldAndReasonOrder() {
        // given
        var fields = violations();

        // when
        var response = ApiErrorResponses.of(ErrorCode.FIELD_INVALID, null, fields);

        // then
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertJson("""
                {"code":"FIELD_INVALID","message":"입력을 확인해 주십시오","fields":[
                  {"field":"purpose","reason":"TOO_LONG"},
                  {"field":"topic","reason":"REQUIRED"},{"field":"topic","reason":"TOO_LONG"}]}
                """, response.getBody());
    }

    @ParameterizedTest(name = "오류 code={0}")
    @EnumSource(value = ErrorCode.class, names = "FIELD_INVALID", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("칸별 오류가 아닌 code에 넘긴 fields는 버린다")
    void discardsFieldsForOtherErrorCodes(ErrorCode code) {
        // given
        var fields = violations();

        // when
        var response = ApiErrorResponses.of(code, null, fields);

        // then
        assertJson("""
                {"code":"%s","message":"%s"}
                """.formatted(code.name(), code.message()), response.getBody());
    }

    @ParameterizedTest(name = "위반 목록={0}")
    @NullAndEmptySource
    @DisplayName("fields가 null이나 빈 목록이면 키를 생략한다")
    void omitsNullOrEmptyFields(List<FieldViolation> fields) {
        // when
        var response = ApiErrorResponses.of(ErrorCode.FIELD_INVALID, null, fields);

        // then
        assertJson("""
                {"code":"FIELD_INVALID","message":"입력을 확인해 주십시오"}
                """, response.getBody());
    }

    @ParameterizedTest(name = "오류 code={0}, 위반 목록={1}")
    @MethodSource("servletFieldCases")
    @DisplayName("서블릿 응답도 모든 code와 위반 목록에서 공통 fields 규칙을 공유한다")
    void sharesFieldRulesWithServletResponse(ErrorCode code, List<FieldViolation> fields) throws Exception {
        // given
        var mapper = JsonMapper.builder().build();
        var response = new MockHttpServletResponse();
        var expected = mapper.writeValueAsString(ApiErrorResponses.of(code, null, fields).getBody());

        // when
        ApiErrorResponses.write(response, mapper, code, null, fields);

        // then
        assertThat(response.getStatus()).isEqualTo(code.status().value());
        assertThat(response.getHeader("Retry-After")).isNull();
        assertThat(new JsonContent(response.getContentAsString(StandardCharsets.UTF_8))).isStrictlyEqualTo(expected);
    }

    @ParameterizedTest(name = "code={0}, 상태={1}, 문구={2}")
    @CsvSource({
            "UNAUTHORIZED,401,로그인이 필요합니다", "CSRF_INVALID,403,요청을 확인할 수 없습니다",
            "NOT_FOUND,404,찾을 수 없습니다", "METHOD_NOT_ALLOWED,405,지원하지 않는 요청 방식입니다",
            "INVALID_REQUEST,400,요청을 확인할 수 없습니다", "INTERNAL_ERROR,500,요청을 처리할 수 없습니다",
            "FIELD_INVALID,400,입력을 확인해 주십시오"
    })
    @DisplayName("도메인 code의 상태와 기본 문구를 쓴다")
    void returnsStatusAndDefaultMessageForErrorCode(ErrorCode code, int status, String message) {
        // when
        var response = ApiErrorResponses.of(code, null);

        // then
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getHeaders().getFirst("Content-Type")).isEqualTo("application/json");
        assertThat(response.getHeaders().getFirst("Retry-After")).isNull();
        assertThat(response.getBody()).isEqualTo(new ApiError(code.name(), message, null, null));
    }

    @ParameterizedTest(name = "원래 상태={0}, code={1}")
    @CsvSource({"400,INVALID_REQUEST", "401,INVALID_REQUEST", "403,INVALID_REQUEST",
            "404,NOT_FOUND", "405,METHOD_NOT_ALLOWED", "406,INVALID_REQUEST",
            "415,INVALID_REQUEST", "500,INTERNAL_ERROR", "503,INTERNAL_ERROR"})
    @DisplayName("표준 오류는 원래 상태와 헤더를 유지한다")
    void preservesOriginalStatusAndHeaders(int status, String code) {
        // given
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.ALLOW, "GET");

        // when
        var response = ApiErrorResponses.forStatus(status, headers);

        // then
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getHeaders().getFirst(HttpHeaders.ALLOW)).isEqualTo("GET");
        assertThat(response.getBody().code()).isEqualTo(code);
    }

    @Test
    @DisplayName("서블릿 응답은 재시도 초를 본문과 헤더에 같이 싣는다")
    void writesRetryDelayInServletBodyAndHeader() throws Exception {
        // given
        var mapper = JsonMapper.builder().build();
        var response = new MockHttpServletResponse();

        // when
        ApiErrorResponses.write(response, mapper, ErrorCode.NOT_FOUND, 37);

        // then
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getHeader("Retry-After")).isEqualTo("37");
        assertThat(new JsonContent(response.getContentAsString(StandardCharsets.UTF_8))).isStrictlyEqualTo("""
                {"code":"NOT_FOUND","message":"찾을 수 없습니다","retryAfterSeconds":37}
                """);
    }

    @Test
    @DisplayName("재시도가 없으면 서블릿 응답의 키와 헤더를 생략한다")
    void omitsRetryDelayFromServletBodyAndHeader() throws Exception {
        // given
        var response = new MockHttpServletResponse();
        var mapper = JsonMapper.builder().build();

        // when
        ApiErrorResponses.write(response, mapper, ErrorCode.NOT_FOUND, null);

        // then
        assertThat(response.getHeader("Retry-After")).isNull();
        assertThat(new JsonContent(response.getContentAsString(StandardCharsets.UTF_8))).isStrictlyEqualTo("""
                {"code":"NOT_FOUND","message":"찾을 수 없습니다"}
                """);
    }

    @ParameterizedTest(name = "재시도 초={0}")
    @ValueSource(ints = {0, -1})
    @DisplayName("도메인 예외는 양수가 아닌 재시도 초를 거부한다")
    void rejectsNonPositiveRetryDelayInException(int seconds) {
        // when & then
        assertThatThrownBy(() -> new ApiException(ErrorCode.NOT_FOUND, seconds))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "재시도 초={0}")
    @ValueSource(ints = {0, -1})
    @DisplayName("공통 오류 응답은 양수가 아닌 재시도 초를 거부한다")
    void rejectsNonPositiveRetryDelayInResponse(int seconds) {
        // when & then
        assertThatThrownBy(() -> ApiErrorResponses.of(ErrorCode.NOT_FOUND, seconds))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<Arguments> servletFieldCases() {
        return Arrays.stream(ErrorCode.values()).flatMap(code ->
                Stream.of(null, List.<FieldViolation>of(), violations()).map(fields -> Arguments.of(code, fields)));
    }

    private static List<FieldViolation> violations() {
        return List.of(new FieldViolation("topic", TOO_LONG), new FieldViolation("topic", REQUIRED),
                new FieldViolation("purpose", TOO_LONG), new FieldViolation("topic", TOO_LONG));
    }

    private static void assertJson(String expected, Object body) {
        assertThat(new JsonContent(JsonMapper.builder().build().writeValueAsString(body))).isStrictlyEqualTo(expected);
    }
}
