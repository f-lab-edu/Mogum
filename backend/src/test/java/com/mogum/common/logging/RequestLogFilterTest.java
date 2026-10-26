package com.mogum.common.logging;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequestEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class RequestLogFilterTest {
    private final RequestLogFilter filter = new RequestLogFilter();

    @AfterEach
    void cleanUp() {
        MDC.clear();
    }

    @Test
    void 필터_밖으로_나가는_예외는_그대로_던지고_500_한_줄과_MDC_정리를_보장한다(CapturedOutput output) {
        var request = new MockHttpServletRequest("GET", "/api/failure");
        var response = new MockHttpServletResponse();
        var failure = new ServletException("test failure");
        assertSame(failure, assertThrows(ServletException.class, () -> filter.doFilter(request, response,
                (req, res) -> {
                    assertEquals(response.getHeader("X-Request-Id"), MDC.get(RequestLogFilter.REQUEST_ID));
                    MDC.put(RequestLogFilter.USER_ID, "42");
                    throw failure;
                })));
        assertNull(MDC.get(RequestLogFilter.REQUEST_ID));
        assertNull(MDC.get(RequestLogFilter.USER_ID));
        filter.requestDestroyed(new ServletRequestEvent(request.getServletContext(), request));
        var lines = output.getOut().lines().filter(line -> line.contains("access method=GET path=/api/failure")).toList();
        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().contains("status=500"));
        assertNull(MDC.get(RequestLogFilter.REQUEST_ID));
        assertNull(MDC.get(RequestLogFilter.USER_ID));
    }

    @Test
    void ERROR_디스패치에도_ID와_회원은_유지하고_최종_응답_상태를_한번만_남긴다(CapturedOutput output) throws Exception {
        var request = new MockHttpServletRequest("GET", "/auth/unknown");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            MDC.put(RequestLogFilter.USER_ID, "42");
            response.sendError(404);
        });
        var id = response.getHeader("X-Request-Id");
        assertNull(MDC.get(RequestLogFilter.USER_ID));
        request.setDispatcherType(DispatcherType.ERROR);
        response.setCommitted(false); // 컨테이너가 ERROR 응답 작성을 위해 응답을 다시 연다.
        response.reset();
        filter.doFilter(request, response, (req, res) -> {
            assertEquals(id, MDC.get(RequestLogFilter.REQUEST_ID));
            assertEquals("42", MDC.get(RequestLogFilter.USER_ID));
            response.setStatus(503);
        });
        assertEquals(id, response.getHeader("X-Request-Id"));
        assertNull(MDC.get(RequestLogFilter.REQUEST_ID));
        assertNull(MDC.get(RequestLogFilter.USER_ID));
        assertFalse(output.getOut().contains("access method="));
        filter.requestDestroyed(new ServletRequestEvent(request.getServletContext(), request));
        filter.requestDestroyed(new ServletRequestEvent(request.getServletContext(), request));
        var lines = output.getOut().lines().filter(line -> line.contains("access method=")).toList();
        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().contains("status=503"));
        assertNull(MDC.get(RequestLogFilter.REQUEST_ID));
        assertNull(MDC.get(RequestLogFilter.USER_ID));
    }
}
