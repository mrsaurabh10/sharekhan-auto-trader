package org.com.sharekhan.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.ErrorAttributes;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.request.ServletWebRequest;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

@Controller
public class AppErrorController implements ErrorController {
    private static final Logger log = LoggerFactory.getLogger(AppErrorController.class);
    private final ErrorAttributes errorAttributes;

    public AppErrorController(ErrorAttributes errorAttributes) {
        this.errorAttributes = errorAttributes;
    }

    @RequestMapping("/error")
    public ResponseEntity<Map<String, Object>> handleError(HttpServletRequest request) {
        try {
            var attrs = errorAttributes.getErrorAttributes(new ServletWebRequest(request), ErrorAttributeOptions.defaults());
            Integer status = (Integer) attrs.getOrDefault("status", 500);
            HttpStatus httpStatus = HttpStatus.resolve(status == null ? 500 : status);
            if (httpStatus == null) httpStatus = HttpStatus.INTERNAL_SERVER_ERROR;
            // log minimal info
            log.warn("Error returned to client: status={} path={}", attrs.getOrDefault("status", "?"), attrs.getOrDefault("path", request.getRequestURI()));
            return ResponseEntity.status(httpStatus).body(attrs);
        } catch (Exception e) {
            log.error("Error while rendering /error", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error","internal_error","message",e.getMessage()));
        }
    }
}
