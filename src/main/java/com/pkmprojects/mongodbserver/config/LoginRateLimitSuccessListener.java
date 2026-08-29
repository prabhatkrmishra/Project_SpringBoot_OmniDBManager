package com.pkmprojects.mongodbserver.config;

import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

/**
 * Clears the login rate-limit window on successful authentication so a
 * legitimate user is not blocked after a few failed attempts followed by
 * a correct password.
 */
@Component
public class LoginRateLimitSuccessListener {

    private final LoginRateLimiter rateLimiter;

    public LoginRateLimitSuccessListener(LoginRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        String username = event.getAuthentication().getName();
        if (username == null || username.isBlank()) return;
        // Clear both IP-only and IP:username keys — we don't have IP here,
        // so clear any key ending with :username. Iterate to find matches.
        // For single-admin single-IP this is cheap; for scale use a reverse index.
        // Simpler: clear the username-suffixed keys by scanning.
        // We cannot know IP, so we clear all keys that end with ":" + username.
        var keys = new java.util.ArrayList<>(rateLimiter.snapshotKeys());
        for (String key : keys) {
            if (key.equals(username) || key.endsWith(":" + username)) {
                rateLimiter.clear(key);
            }
        }
    }
}
