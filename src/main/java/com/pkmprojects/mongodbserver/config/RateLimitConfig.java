package com.pkmprojects.mongodbserver.config;

import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the login rate-limiter ahead of the Spring Security filter chain so
 * brute-force attempts are throttled before authentication is even attempted.
 */
@Configuration(proxyBeanMethods = false)
public class RateLimitConfig {

    @Bean
    FilterRegistrationBean<LoginRateLimitFilter> loginRateLimitFilter(LoginRateLimiter rateLimiter,
                                                                      LoginRateLimitProperties properties) {
        FilterRegistrationBean<LoginRateLimitFilter> registration =
                new FilterRegistrationBean<>(new LoginRateLimitFilter(rateLimiter, properties));
        registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 10);
        return registration;
    }

    @Bean
    FilterRegistrationBean<ProvisionRateLimitFilter> provisionRateLimitFilter(LoginRateLimiter rateLimiter,
                                                                              ProvisionRateLimitProperties properties) {
        FilterRegistrationBean<ProvisionRateLimitFilter> registration =
                new FilterRegistrationBean<>(new ProvisionRateLimitFilter(rateLimiter, properties));
        registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 9);
        return registration;
    }
}
