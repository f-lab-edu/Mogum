package com.mogum.common.logging;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 모든 필터 바깥에서 상관 ID를 붙이고, ERROR 처리까지 끝난 요청의 접근 줄을 만든다. */
public final class RequestLogFilter implements Filter, ServletRequestListener {
    public static final String REQUEST_ID = "requestId";
    public static final String USER_ID = "userId";
    private static final String STATE = RequestLogFilter.class.getName() + ".state";
    private static final String HEADER = "X-Request-Id";
    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {
        var request = (HttpServletRequest) servletRequest;
        var response = (HttpServletResponse) servletResponse;
        var state = (RequestState) request.getAttribute(STATE);
        if (state == null) {
            state = new RequestState(request, response);
            request.setAttribute(STATE, state);
        }
        MDC.put(REQUEST_ID, state.id);
        MDC.remove(USER_ID);
        if (state.userId != null) MDC.put(USER_ID, state.userId);
        response.setHeader(HEADER, state.id);
        boolean dispatchFailed = false;
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException | Error exception) {
            dispatchFailed = true;
            state.failed = true;
            throw exception;
        } finally {
            state.userId = MDC.get(USER_ID);
            // 체인 밖에서 컨테이너가 찍는 ERROR까지 ID를 유지하고 종료 리스너에서 지운다.
            if (!dispatchFailed) clearRequestMdc();
        }
    }

    @Override
    public void requestDestroyed(ServletRequestEvent event) {
        var request = event.getServletRequest();
        var state = (RequestState) request.getAttribute(STATE);
        if (state == null) return;
        try {
            MDC.put(REQUEST_ID, state.id);
            MDC.remove(USER_ID);
            if (state.userId != null) MDC.put(USER_ID, state.userId);
            if (!("GET".equals(state.method) && "/actuator/health".equals(state.path))) {
                log.info("access method={} path={} status={} durationMs={}", state.method, state.path,
                        state.failed ? 500 : state.response.getStatus(),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - state.started));
            }
        } finally {
            clearRequestMdc();
            request.removeAttribute(STATE);
        }
    }

    private static void clearRequestMdc() {
        MDC.remove(REQUEST_ID);
        MDC.remove(USER_ID);
    }

    /** 공유 필드가 아니라 서블릿 요청 속성 하나의 수명에만 속한다. */
    private static final class RequestState {
        private final String id = UUID.randomUUID().toString();
        private final long started = System.nanoTime();
        private final String method;
        private final String path;
        private final HttpServletResponse response;
        private String userId;
        private boolean failed;

        private RequestState(HttpServletRequest request, HttpServletResponse response) {
            this.method = request.getMethod();
            this.path = request.getRequestURI();
            this.response = response;
        }
    }
}
