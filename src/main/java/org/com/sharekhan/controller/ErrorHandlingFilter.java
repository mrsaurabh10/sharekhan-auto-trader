package org.com.sharekhan.controller;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
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
        // Wrap response to buffer output
        BufferingResponseWrapper wrapped = new BufferingResponseWrapper(res);
        try {
            chain.doFilter(request, wrapped);
            // Success: commit buffered content to actual response
            // log and commit
            try {
                int size = wrapped.getBufferSizeEstimate();
                log.debug("Committing buffered response for {} {} - bytes={}", req.getMethod(), req.getRequestURI(), size);
            } catch (Exception _e) { /* ignore */ }
            wrapped.commitToResponse();
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

    // Simple buffering wrapper that captures writes and can commit the buffered bytes to underlying response
    private static class BufferingResponseWrapper extends HttpServletResponseWrapper {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final ServletOutputStream outputStream = new ServletOutputStream() {
            private boolean closed = false;
            @Override
            public boolean isReady() { return !closed; }
            @Override
            public void setWriteListener(WriteListener writeListener) { /* no-op */ }
            @Override
            public void write(int b) throws IOException { buffer.write(b); }
            @Override
            public void close() throws IOException { closed = true; super.close(); }
        };
        private PrintWriter writer;
        private final HttpServletResponse original;

        public BufferingResponseWrapper(HttpServletResponse response) {
            super(response);
            this.original = response;
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            return outputStream;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (writer == null) writer = new PrintWriter(new OutputStreamWriter(buffer, getCharacterEncoding() != null ? getCharacterEncoding() : StandardCharsets.UTF_8.name()));
            return writer;
        }

        public void commitToResponse() throws IOException {
            // flush writers
            try { if (writer != null) writer.flush(); } catch (Exception ignored) {}
            byte[] bytes = buffer.toByteArray();
            original.setContentLength(bytes.length);
            log.debug("commitToResponse writing {} bytes to real response", bytes.length);
            try (var out = original.getOutputStream()) {
                out.write(bytes);
                out.flush();
            } catch (IOException io) {
                // Log and rethrow so outer filter can attempt a graceful fallback
                log.error("IOException while committing buffered response for path {}: {}", original.getHeaderNames(), io.toString());
                throw io;
            }
         }

        public int getBufferSizeEstimate() {
            return buffer.size();
        }
     }
 }
