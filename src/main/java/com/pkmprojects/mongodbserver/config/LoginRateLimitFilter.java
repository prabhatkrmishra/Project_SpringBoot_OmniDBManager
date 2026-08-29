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
 * Rate limits {@code POST /login} per client (IP + submitted username) to blunt
 * credential brute-forcing. Runs ahead of the security filter chain, so excess
 * attempts are rejected before any authentication work happens.
 */
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private final LoginRateLimiter rateLimiter;
    private final LoginRateLimitProperties properties;

    public LoginRateLimitFilter(LoginRateLimiter rateLimiter, LoginRateLimitProperties properties) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !"/login".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        if (rateLimiter.isAllowed(clientKey(request), properties.maxAttempts(), properties.window())) {
            filterChain.doFilter(request, response);
            return;
        }
        // Render login page with rate-limit banner instead of raw 429 text,
        // so the user sees a proper UI and can retry after Retry-After.
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(Math.max(1, properties.window().toSeconds())));
        // If the request expects HTML (browser form POST), redirect to login with flag.
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains("text/html")) {
            response.sendRedirect(request.getContextPath() + "/login?rateLimited");
            return;
        }
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write("Too many login attempts. Please try again later.");
    }

    private String clientKey(HttpServletRequest request) {
        String clientIp = request.getRemoteAddr();
        // X-Forwarded-For is client-spoofable; only honor it when the app is
        // known to sit behind a trusted reverse proxy that overwrites it.
        if (properties.trustXForwardedFor()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                clientIp = forwarded.split(",")[0].trim();
            }
        }
        String username = request.getParameter("username");
        if (username == null || username.isBlank()) {
            return clientIp;
        }
        return clientIp + ":" + username;
    }
}
