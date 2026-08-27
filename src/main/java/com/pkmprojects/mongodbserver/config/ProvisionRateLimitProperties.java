package com.pkmprojects.mongodbserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Per-engine rate limit for provision/reset/delete, bound from {@code app.provision-rate-limit.*}.
 */
@ConfigurationProperties(prefix = "app.provision-rate-limit")
public record ProvisionRateLimitProperties(int maxAttempts, Duration window, boolean trustXForwardedFor) {

    public ProvisionRateLimitProperties {
        if (maxAttempts <= 0) maxAttempts = 5;
        if (window == null) window = Duration.ofMinutes(1);
    }
}
