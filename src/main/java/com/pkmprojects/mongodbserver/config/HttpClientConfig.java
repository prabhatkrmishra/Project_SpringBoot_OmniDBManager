package com.pkmprojects.mongodbserver.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Shared HTTP client for outbound calls (webhook delivery and proxy filters).
 * Uses HTTP/1.1 for widest compatibility with bundled UIs (mongo-express/Adminer/phpMyAdmin).
 * Per-request timeout is set by callers (e.g. WebhookNotifier 10s, proxy filters 60s).
 */
@Configuration(proxyBeanMethods = false)
public class HttpClientConfig {

    @Bean
    HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}