package com.mogum.common;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

class ApiErrorControllerTest {
    @Test
    void 컨테이너_상태만_읽고_오류_상세는_노출하지_않는다() {
        var request = new MockHttpServletRequest();
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 400);
        request.setAttribute(RequestDispatcher.ERROR_MESSAGE, "private request input");
        request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("private failure"));
        var response = new ApiErrorController().error(request);
        assertEquals(400, response.getStatusCode().value());
        assertEquals(new ApiError("INVALID_REQUEST", "요청을 확인할 수 없습니다", null), response.getBody());
        assertNull(response.getHeaders().getFirst("Retry-After"));
        assertEquals("application/json", response.getHeaders().getFirst("Content-Type"));
    }

    @Test
    void 컨테이너_상태가_없으면_공통_500이다() {
        var response = new ApiErrorController().error(new MockHttpServletRequest());
        assertEquals(500, response.getStatusCode().value());
        assertEquals(new ApiError("INTERNAL_ERROR", "요청을 처리할 수 없습니다", null), response.getBody());
    }

    @Test
    void 컨테이너의_503도_500으로_바꾸지_않는다() {
        var request = new MockHttpServletRequest();
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 503);
        var response = new ApiErrorController().error(request);
        assertEquals(503, response.getStatusCode().value());
        assertEquals(new ApiError("INTERNAL_ERROR", "요청을 처리할 수 없습니다", null), response.getBody());
    }
}
