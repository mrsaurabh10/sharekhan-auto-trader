package org.com.sharekhan.controller;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ErrorHandlingFilter implements jakarta.servlet.Filter {
    private static final Logger log = LoggerFactory.getLogger(ErrorHandlingFilter.class);

    @Override
    public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        try {
            chain.doFilter(request, response);
        } catch (Throwable t) {
            // Log full stack server-side
            log.error("Unhandled throwable while processing request {} {}", req.getMethod(), req.getRequestURI(), t);
            try {
                if (!res.isCommitted()) {
                    res.reset();
                    res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    res.setContentType("application/json;charset=UTF-8");
                    Map<String, Object> body = Map.of(
                            "error", "internal_server_error",
                            "message", t.getMessage() == null ? "Unexpected error" : t.getMessage(),
                            "path", req.getRequestURI()
                    );
                    String json = "{" +
                            "\"error\":\"" + escapeJson((String) body.get("error")) + "\"," +
                            "\"message\":\"" + escapeJson((String) body.get("message")) + "\"," +
                            "\"path\":\"" + escapeJson((String) body.get("path")) + "\"" +
                            "}";
                    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                    res.setContentLength(bytes.length);
                    log.debug("Writing fallback error body for {} {} - bytes={}", req.getMethod(), req.getRequestURI(), bytes.length);
                    try (PrintWriter w = res.getWriter()) { w.write(json); w.flush(); }
                } else {
                    log.warn("Response already committed - cannot write error body for {} {}", req.getMethod(), req.getRequestURI());
                }
            } catch (Exception ex) {
                log.error("Failed to write fallback error response", ex);
            }
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
