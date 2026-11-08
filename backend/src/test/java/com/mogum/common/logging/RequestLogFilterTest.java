package com.mogum.common.logging;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequestEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class RequestLogFilterTest {
    private final RequestLogFilter filter = new RequestLogFilter();

    @BeforeEach
    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("필터 밖으로 나가는 예외는 원래 예외와 요청 및 회원 MDC를 유지한다")
    void preservesExceptionAndMdcUntilRequestCompletion() {
        // given
        var request = new MockHttpServletRequest("GET", "/api/failure");
        var response = new MockHttpServletResponse();
        var failure = new ServletException("test failure");

        // when & then
        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            assertThat(MDC.get(RequestLogFilter.REQUEST_ID)).isEqualTo(response.getHeader("X-Request-Id"));
            MDC.put(RequestLogFilter.USER_ID, "42");
            throw failure;
        })).isSameAs(failure);
        assertThat(MDC.get(RequestLogFilter.REQUEST_ID)).isEqualTo(response.getHeader("X-Request-Id"));
        assertThat(MDC.get(RequestLogFilter.USER_ID)).isEqualTo("42");
    }

    @Test
    @DisplayName("필터 예외 뒤 요청 종료는 500 접근 줄 하나를 남기고 MDC를 지운다")
    void logsFailureOnceAndClearsMdcOnCompletion(CapturedOutput output) {
        // given
        var request = new MockHttpServletRequest("GET", "/api/failure");
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            MDC.put(RequestLogFilter.USER_ID, "42");
            throw new ServletException("test failure");
        })).isInstanceOf(ServletException.class);

        // when
        filter.requestDestroyed(new ServletRequestEvent(request.getServletContext(), request));

        // then
        assertThat(accessLines(output, "/api/failure")).singleElement().asString().contains("status=500");
        assertThat(MDC.get(RequestLogFilter.REQUEST_ID)).isNull();
        assertThat(MDC.get(RequestLogFilter.USER_ID)).isNull();
    }

    @Test
    @DisplayName("정상 REQUEST 디스패치는 회원 MDC를 지우고 종료 전에는 접근 줄을 남기지 않는다")
    void clearsMdcWithoutLoggingBeforeCompletion(CapturedOutput output) throws Exception {
        // given
        var request = new MockHttpServletRequest("GET", "/auth/unknown");
        var response = new MockHttpServletResponse();

        // when
        filter.doFilter(request, response, (req, res) -> {
            MDC.put(RequestLogFilter.USER_ID, "42");
            response.sendError(404);
        });

        // then
        assertThat(MDC.get(RequestLogFilter.USER_ID)).isNull();
        assertThat(output.getOut()).doesNotContain("access method=");
    }

    @Test
    @DisplayName("ERROR 디스패치는 요청과 회원 ID를 복원하고 완료 뒤 MDC를 지우며 종료 전에는 기록하지 않는다")
    void restoresIdsDuringErrorDispatchAndClearsMdcAfterward(CapturedOutput output) throws Exception {
        // given
        var request = new MockHttpServletRequest("GET", "/auth/unknown");
        var response = new MockHttpServletResponse();
        prepareErrorDispatch(request, response);
        var id = response.getHeader("X-Request-Id");
        response.reset();

        // when
        filter.doFilter(request, response, (req, res) -> {
            assertThat(MDC.get(RequestLogFilter.REQUEST_ID)).isEqualTo(id);
            assertThat(MDC.get(RequestLogFilter.USER_ID)).isEqualTo("42");
            response.setStatus(503);
        });

        // then
        assertThat(response.getHeader("X-Request-Id")).isEqualTo(id);
        assertThat(MDC.get(RequestLogFilter.REQUEST_ID)).isNull();
        assertThat(MDC.get(RequestLogFilter.USER_ID)).isNull();
        assertThat(output.getOut()).doesNotContain("access method=");
    }

    @Test
    @DisplayName("ERROR 처리 뒤 요청 종료는 최종 응답 상태를 한 번 기록하고 MDC를 지운다")
    void logsFinalErrorDispatchStatusOnCompletion(CapturedOutput output) throws Exception {
        // given
        var request = new MockHttpServletRequest("GET", "/auth/unknown");
        var response = new MockHttpServletResponse();
        prepareErrorDispatch(request, response);
        filter.doFilter(request, response, (req, res) -> response.setStatus(503));

        // when
        filter.requestDestroyed(new ServletRequestEvent(request.getServletContext(), request));

        // then
        assertThat(accessLines(output, "/auth/unknown")).singleElement().asString().contains("status=503");
        assertThat(MDC.get(RequestLogFilter.REQUEST_ID)).isNull();
        assertThat(MDC.get(RequestLogFilter.USER_ID)).isNull();
    }

    @Test
    @DisplayName("종료 리스너가 중복 호출되어도 접근 줄을 추가하지 않고 MDC는 비어 있다")
    void ignoresDuplicateCompletion(CapturedOutput output) throws Exception {
        // given
        var request = new MockHttpServletRequest("GET", "/auth/unknown");
        var response = new MockHttpServletResponse();
        prepareErrorDispatch(request, response);
        filter.doFilter(request, response, (req, res) -> response.setStatus(503));
        var event = new ServletRequestEvent(request.getServletContext(), request);
        filter.requestDestroyed(event);

        // when
        filter.requestDestroyed(event);

        // then
        assertThat(accessLines(output, "/auth/unknown")).singleElement().asString().contains("status=503");
        assertThat(MDC.get(RequestLogFilter.REQUEST_ID)).isNull();
        assertThat(MDC.get(RequestLogFilter.USER_ID)).isNull();
    }

    private void prepareErrorDispatch(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        filter.doFilter(request, response, (req, res) -> {
            MDC.put(RequestLogFilter.USER_ID, "42");
            response.sendError(404);
        });
        request.setDispatcherType(DispatcherType.ERROR);
        response.setCommitted(false); // 컨테이너가 ERROR 응답 작성을 위해 응답을 다시 연다.
    }

    private static List<String> accessLines(CapturedOutput output, String path) {
        return output.getOut().lines().filter(line -> line.contains("access method=GET path=" + path)).toList();
    }
}
