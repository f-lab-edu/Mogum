package com.mogum.common;

import com.mogum.account.controller.MeController;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiExceptionHandlerTest {
    @Test
    void 없거나_남의_리소스는_공통_404로_변환한다() {
        var response = new ApiExceptionHandler().apiException(new ApiException(ErrorCode.NOT_FOUND));
        assertEquals(404, response.getStatusCode().value());
        assertEquals("NOT_FOUND", response.getBody().code());
        assertEquals("찾을 수 없습니다", response.getBody().message());
        assertNull(response.getHeaders().getFirst("Retry-After"));
    }

    @Test
    void 도메인_오류는_선택적_재시도_초를_본문과_헤더에_같이_싣는다() throws Exception {
        var controller = mock(MeController.class);
        when(controller.me(null)).thenThrow(new ApiException(ErrorCode.NOT_FOUND, 37));
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(get("/api/me")).andExpect(status().isNotFound())
                .andExpect(header().string("Retry-After", "37"))
                .andExpect(content().json("""
                        {"code":"NOT_FOUND","message":"찾을 수 없습니다","retryAfterSeconds":37}
                        """, JsonCompareMode.STRICT));
        doThrow(new ApiException(ErrorCode.NOT_FOUND)).when(controller).me(null);
        mvc.perform(get("/api/me")).andExpect(status().isNotFound())
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(content().json("""
                        {"code":"NOT_FOUND","message":"찾을 수 없습니다"}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void MVC의_지원하지_않는_본문_형식은_415를_유지한다() throws Exception {
        var request = new ServletWebRequest(new MockHttpServletRequest(), new MockHttpServletResponse());
        var response = new ApiExceptionHandler().handleException(
                new HttpMediaTypeNotSupportedException("private content type"), request);
        assertEquals(415, response.getStatusCode().value());
        assertEquals(new ApiError("INVALID_REQUEST", "요청을 확인할 수 없습니다", null), response.getBody());
    }

    @Test
    void MVC의_메서드_오류는_상태와_Allow_헤더를_보존한다() throws Exception {
        var request = new ServletWebRequest(new MockHttpServletRequest(), new MockHttpServletResponse());
        var response = new ApiExceptionHandler().handleException(
                new HttpRequestMethodNotSupportedException("POST", List.of("GET")), request);
        assertEquals(405, response.getStatusCode().value());
        assertEquals("GET", response.getHeaders().getFirst("Allow"));
        assertEquals(new ApiError("METHOD_NOT_ALLOWED", "지원하지 않는 요청 방식입니다", null), response.getBody());
    }

    @Test
    void 잘못된_JSON은_상세_원문_없이_공통_400으로_변환한다() throws Exception {
        var request = new ServletWebRequest(new MockHttpServletRequest(), new MockHttpServletResponse());
        var response = new ApiExceptionHandler().handleException(
                new HttpMessageNotReadableException("private JSON input", new MockHttpInputMessage(new byte[0])), request);
        assertEquals(400, response.getStatusCode().value());
        assertEquals(new ApiError("INVALID_REQUEST", "요청을 확인할 수 없습니다", null), response.getBody());
    }

    @Test
    void 처리되지_않은_컨트롤러_예외는_상세_원문_없이_공통_JSON_500이다() throws Exception {
        var controller = mock(MeController.class);
        when(controller.me(null)).thenThrow(new IllegalStateException("private controller failure"));
        MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new ApiExceptionHandler()).build()
                .perform(get("/api/me")).andExpect(status().isInternalServerError())
                .andExpect(content().json("""
                        {"code":"INTERNAL_ERROR","message":"요청을 처리할 수 없습니다"}
                        """, org.springframework.test.json.JsonCompareMode.STRICT));
    }
}
