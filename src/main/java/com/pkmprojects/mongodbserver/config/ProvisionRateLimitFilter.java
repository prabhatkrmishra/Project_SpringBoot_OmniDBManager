package com.pkmprojects.mongodbserver.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Per-engine rate limit for provision/reset/delete (POST /mongo/** and POST /postgres/**).
 * Key is IP + engine so one engine cannot starve the other.
 */
public class ProvisionRateLimitFilter extends OncePerRequestFilter {

    private final LoginRateLimiter rateLimiter;
    private final ProvisionRateLimitProperties properties;

    public ProvisionRateLimitFilter(LoginRateLimiter rateLimiter, ProvisionRateLimitProperties properties) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!HttpMethod.POST.matches(request.getMethod())) return true;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        // Only throttle provision/reset/delete and backup/restore
        boolean isProvision = path.equals("/mongo/databases") || path.equals("/postgres/databases");
        boolean isReset = path.matches(".*/(mongo|postgres)/databases/[^/]+/reset");
        boolean isDelete = path.matches(".*/(mongo|postgres)/databases/[^/]+/delete")
                || path.matches(".*/(mongo|postgres)/databases/[^/]+/users/[^/]+/delete");
        boolean isBackupRestore = path.matches(".*/(mongo|postgres)/databases/[^/]+/(backup|restore)")
                || path.matches(".*/databases/[^/]+/(backup|restore)");
        return !(isProvision || isReset || isDelete || isBackupRestore);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String engine = request.getRequestURI().contains("/postgres/") ? "POSTGRES" : "MONGO";
        // legacy /databases/* without prefix -> treat as MONGO for rate limit bucket
        if (request.getRequestURI().startsWith("/databases/")) engine = "MONGO";
        String key = clientKey(request) + ":" + engine;
        if (rateLimiter.isAllowed(key, properties.maxAttempts(), properties.window())) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(Math.max(1, properties.window().toSeconds())));
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write("Too many requests for " + engine + ". Please try again later.");
    }

    private String clientKey(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        if (properties.trustXForwardedFor()) {
            String fwd = request.getHeader("X-Forwarded-For");
            if (fwd != null && !fwd.isBlank()) ip = fwd.split(",")[0].trim();
        }
        return ip;
    }
}
