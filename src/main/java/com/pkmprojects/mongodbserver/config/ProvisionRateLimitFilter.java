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
        String raw = request.getRequestURI().substring(request.getContextPath().length());
        // Normalize trailing slash so /postgres/databases/ matches provision
        String path = raw.endsWith("/") && raw.length() > 1 ? raw.substring(0, raw.length() - 1) : raw;
        boolean isProvision = path.equals("/mongo/databases") || path.equals("/postgres/databases")
                || path.equals("/mysql/databases")
                || path.equals("/databases");
        boolean isReset = path.matches(".*/(mongo|postgres|mysql)/databases/[^/]+/reset")
                || path.matches(".*/databases/[^/]+/reset");
        boolean isDelete = path.matches(".*/(mongo|postgres|mysql)/databases/[^/]+/delete")
                || path.matches(".*/(mongo|postgres|mysql)/databases/[^/]+/users/[^/]+/delete")
                || path.matches(".*/databases/[^/]+/delete")
                || path.matches(".*/databases/[^/]+/users/[^/]+/delete")
                || path.matches(".*/databases/[^/]+/collections/[^/]+/delete")
                || path.matches(".*/postgres/databases/[^/]+/tables/[^/]+/delete")
                || path.matches(".*/postgres/databases/[^/]+/tables/[^/]+/rows/delete")
                || path.matches(".*/mysql/databases/[^/]+/tables/[^/]+/delete")
                || path.matches(".*/mysql/databases/[^/]+/tables/[^/]+/truncate")
                || path.matches(".*/mysql/databases/[^/]+/tables/[^/]+/rows/delete");
        boolean isBackupRestore = path.matches(".*/(mongo|postgres|mysql)/databases/[^/]+/(backup|restore)")
                || path.matches(".*/databases/[^/]+/(backup|restore)");
        boolean isCollectionCreate = path.matches(".*/databases/[^/]+/collections");
        boolean isImport = path.matches(".*/databases/[^/]+/collections/[^/]+/import");
        boolean isPgTableWrite = path.matches(".*/postgres/databases/[^/]+/tables")
                || path.matches(".*/postgres/databases/[^/]+/tables/[^/]+/truncate")
                || path.matches(".*/postgres/databases/[^/]+/tables/[^/]+/rows");
        boolean isMysqlTableWrite = path.matches(".*/mysql/databases/[^/]+/tables")
                || path.matches(".*/mysql/databases/[^/]+/tables/[^/]+/truncate")
                || path.matches(".*/mysql/databases/[^/]+/tables/[^/]+/rows");
        return !(isProvision || isReset || isDelete || isBackupRestore || isCollectionCreate || isImport || isPgTableWrite || isMysqlTableWrite);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        String path = uri.substring(request.getContextPath().length());
        String engine;
        if (path.startsWith("/postgres/") || path.equals("/postgres")) {
            engine = "POSTGRES";
        } else if (path.startsWith("/mysql/") || path.equals("/mysql")) {
            engine = "MYSQL";
        } else if (path.startsWith("/mongo/") || path.equals("/mongo")) {
            engine = "MONGO";
        } else {
            // legacy /databases/* and /provision etc -> treat as MONGO bucket
            engine = "MONGO";
        }
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
