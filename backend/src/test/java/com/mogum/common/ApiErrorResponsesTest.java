package com.mogum.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.json.JsonAssert;
import org.springframework.test.json.JsonCompareMode;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;

import static com.mogum.common.FieldViolation.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

class ApiErrorResponsesTest {
    @Test
    void 칸별_위반은_중복_없이_칸과_reason_이름순으로_직렬화한다() {
        var response = ApiErrorResponses.of(ErrorCode.FIELD_INVALID, null, violations());
        assertEquals(400, response.getStatusCode().value());
        JsonAssert.comparator(JsonCompareMode.STRICT).assertIsMatch("""
                {"code":"FIELD_INVALID","message":"입력을 확인해 주십시오","fields":[
                  {"field":"purpose","reason":"TOO_LONG"},
                  {"field":"topic","reason":"REQUIRED"},{"field":"topic","reason":"TOO_LONG"}]}
                """, JsonMapper.builder().build().writeValueAsString(response.getBody()));
    }

    @Test
    void 다른_code에_넘긴_fields는_버린다() {
        for (var code : ErrorCode.values()) {
            if (code == ErrorCode.FIELD_INVALID) continue;
            var response = ApiErrorResponses.of(code, null, violations());
            JsonAssert.comparator(JsonCompareMode.STRICT).assertIsMatch("""
                    {"code":"%s","message":"%s"}
                    """.formatted(code.name(), code.message()),
                    JsonMapper.builder().build().writeValueAsString(response.getBody()));
        }
    }

    @Test
    void fields가_null이나_빈_목록이면_키를_생략한다() {
        for (var fields : java.util.Arrays.asList(null, List.<FieldViolation>of())) {
            var response = ApiErrorResponses.of(ErrorCode.FIELD_INVALID, null, fields);
            JsonAssert.comparator(JsonCompareMode.STRICT).assertIsMatch("""
                    {"code":"FIELD_INVALID","message":"입력을 확인해 주십시오"}
                    """, JsonMapper.builder().build().writeValueAsString(response.getBody()));
        }
    }

    @Test
    void 서블릿_응답도_fields_규칙을_공유한다() throws Exception {
        var mapper = JsonMapper.builder().build();
        for (var code : ErrorCode.values()) {
            for (var fields : java.util.Arrays.asList(null, List.<FieldViolation>of(), violations())) {
                var response = new MockHttpServletResponse();
                ApiErrorResponses.write(response, mapper, code, null, fields);
                assertEquals(code.status().value(), response.getStatus());
                assertNull(response.getHeader("Retry-After"));
                JsonAssert.comparator(JsonCompareMode.STRICT).assertIsMatch(
                        mapper.writeValueAsString(ApiErrorResponses.of(code, null, fields).getBody()),
                        response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
    }

    private static List<FieldViolation> violations() {
        return List.of(new FieldViolation("topic", TOO_LONG), new FieldViolation("topic", REQUIRED),
                new FieldViolation("purpose", TOO_LONG), new FieldViolation("topic", TOO_LONG));
    }

    @ParameterizedTest
    @CsvSource({
            "UNAUTHORIZED,401,로그인이 필요합니다", "CSRF_INVALID,403,요청을 확인할 수 없습니다",
            "NOT_FOUND,404,찾을 수 없습니다", "METHOD_NOT_ALLOWED,405,지원하지 않는 요청 방식입니다",
            "INVALID_REQUEST,400,요청을 확인할 수 없습니다", "INTERNAL_ERROR,500,요청을 처리할 수 없습니다",
            "FIELD_INVALID,400,입력을 확인해 주십시오"
    })
    void 도메인_code의_상태와_기본_문구를_쓴다(ErrorCode code, int status, String message) {
        var response = ApiErrorResponses.of(code, null);
        assertEquals(status, response.getStatusCode().value());
        assertEquals("application/json", response.getHeaders().getFirst("Content-Type"));
        assertNull(response.getHeaders().getFirst("Retry-After"));
        assertEquals(new ApiError(code.name(), message, null, null), response.getBody());
    }

    @ParameterizedTest
    @CsvSource({"400,INVALID_REQUEST", "401,INVALID_REQUEST", "403,INVALID_REQUEST",
            "404,NOT_FOUND", "405,METHOD_NOT_ALLOWED", "406,INVALID_REQUEST",
            "415,INVALID_REQUEST", "500,INTERNAL_ERROR", "503,INTERNAL_ERROR"})
    void 표준_오류는_원래_상태와_헤더를_유지한다(int status, String code) {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.ALLOW, "GET");
        var response = ApiErrorResponses.forStatus(status, headers);
        assertEquals(status, response.getStatusCode().value());
        assertEquals("GET", response.getHeaders().getFirst(HttpHeaders.ALLOW));
        assertEquals(code, response.getBody().code());
    }

    @Test
    void 서블릿_응답도_같은_본문과_재시도_헤더를_쓴다() throws Exception {
        var mapper = JsonMapper.builder().build();
        var response = new MockHttpServletResponse();
        ApiErrorResponses.write(response, mapper, ErrorCode.NOT_FOUND, 37);
        assertEquals(404, response.getStatus());
        assertEquals("application/json", response.getContentType());
        assertEquals("37", response.getHeader("Retry-After"));
        JsonAssert.comparator(JsonCompareMode.STRICT).assertIsMatch("""
                {"code":"NOT_FOUND","message":"찾을 수 없습니다","retryAfterSeconds":37}
                """, response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void 재시도가_없으면_서블릿_응답의_키와_헤더를_생략한다() throws Exception {
        var response = new MockHttpServletResponse();
        ApiErrorResponses.write(response, JsonMapper.builder().build(), ErrorCode.NOT_FOUND, null);
        assertNull(response.getHeader("Retry-After"));
        JsonAssert.comparator(JsonCompareMode.STRICT).assertIsMatch("""
                {"code":"NOT_FOUND","message":"찾을 수 없습니다"}
                """, response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void 재시도_초는_양수만_허용한다() {
        assertThrows(IllegalArgumentException.class, () -> new ApiException(ErrorCode.NOT_FOUND, 0));
        assertThrows(IllegalArgumentException.class, () -> new ApiException(ErrorCode.NOT_FOUND, -1));
        assertThrows(IllegalArgumentException.class, () -> ApiErrorResponses.of(ErrorCode.NOT_FOUND, 0));
    }
}
