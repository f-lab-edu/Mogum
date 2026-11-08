package com.mogum.common.api;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ApiErrorControllerTest {
    @Test
    @DisplayName("컨테이너 상태만 읽고 오류 상세는 노출하지 않는다")
    void returnsContainerStatusWithoutPrivateDetails() {
        // given
        var request = new MockHttpServletRequest();
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 400);
        request.setAttribute(RequestDispatcher.ERROR_MESSAGE, "private request input");
        request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("private failure"));

        // when
        var response = new ApiErrorController().error(request);

        // then
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isEqualTo(new ApiError("INVALID_REQUEST", "요청을 확인할 수 없습니다", null, null));
        assertThat(response.getHeaders().getFirst("Retry-After")).isNull();
        assertThat(response.getHeaders().getFirst("Content-Type")).isEqualTo("application/json");
    }

    @Test
    @DisplayName("컨테이너 상태가 없으면 공통 500 오류를 반환한다")
    void defaultsToInternalErrorWhenStatusIsMissing() {
        // given
        var request = new MockHttpServletRequest();

        // when
        var response = new ApiErrorController().error(request);

        // then
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isEqualTo(new ApiError("INTERNAL_ERROR", "요청을 처리할 수 없습니다", null, null));
    }

    @Test
    @DisplayName("컨테이너의 503 상태를 보존한다")
    void preservesServiceUnavailableStatus() {
        // given
        var request = new MockHttpServletRequest();
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 503);

        // when
        var response = new ApiErrorController().error(request);

        // then
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isEqualTo(new ApiError("INTERNAL_ERROR", "요청을 처리할 수 없습니다", null, null));
    }
}
