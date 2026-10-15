package org.com.sharekhan.onboarding;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Per-instance protection. Uses the actual peer address, never a client-supplied forwarding header. */
public class AccountRateLimitFilter extends OncePerRequestFilter {
    private record Window(long starts, int count) {}
    private final Map<String, Window> attempts = new HashMap<>();
    synchronized boolean allow(String key, long now) {
        attempts.entrySet().removeIf(e -> now - e.getValue().starts() >= 900_000);
        Window w = attempts.getOrDefault(key, new Window(now, 0));
        if (w.count() >= 20 || (!attempts.containsKey(key) && attempts.size() >= 10_000)) return false;
        attempts.put(key, new Window(w.starts(), w.count() + 1)); return true;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        if ("POST".equals(request.getMethod()) && (path.startsWith("/api/account/") || path.equals("/login"))
                && !allow(request.getRemoteAddr(), System.currentTimeMillis())) {
            response.setStatus(429); response.setHeader("Retry-After", "900");
            response.setContentType("application/json"); response.getWriter().write("{\"message\":\"Too many attempts. Try again in 15 minutes.\"}"); return;
        }
        chain.doFilter(request, response);
    }
}
