package com.mogum.api;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** MVC까지 도달하지 않은 sendError/예외도 같은 JSON으로 응답한다. */
@RestController
public class ApiErrorController implements ErrorController {
    @RequestMapping("${server.error.path:/error}")
    public ResponseEntity<ApiError> error(HttpServletRequest request) {
        var attribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = attribute instanceof Integer value ? value : 500;
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(ApiError.forStatus(status));
    }
}
