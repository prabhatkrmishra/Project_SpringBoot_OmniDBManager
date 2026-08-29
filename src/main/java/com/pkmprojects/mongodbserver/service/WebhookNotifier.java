package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.model.AuditEvent;
import com.pkmprojects.mongodbserver.model.AuditEventRecorded;
import com.pkmprojects.mongodbserver.model.WebhookConfig;
import com.pkmprojects.mongodbserver.store.WebhookConfigStore;
import com.pkmprojects.mongodbserver.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Delivers admin-action notifications to configured webhook endpoints. Listens
 * for {@link AuditEventRecorded} events, fans out to every enabled webhook that
 * subscribes to the event type, and POSTs a JSON payload asynchronously so a
 * slow or unreachable endpoint never blocks the admin action that triggered it.
 *
 * <p>Payloads are signed with HMAC-SHA256 ({@code X-Webhook-Signature:
 * sha256=<hex>}) when the webhook has a secret. Delivery retries transient
 * failures up to {@value #MAX_DELIVERY_ATTEMPTS} times.
 */
@Service
public class WebhookNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookNotifier.class);

    static final int MAX_DELIVERY_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MILLIS = 500;
    private static final int MAX_CONCURRENT_DELIVERIES = 8;
    private static final int MAX_QUEUED_DELIVERIES = 128;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final WebhookConfigStore webhookConfigStore;
    private final HttpClient httpClient;
    private final ExecutorService executor;

    @Autowired
    public WebhookNotifier(WebhookConfigStore webhookConfigStore, HttpClient httpClient) {
        this(webhookConfigStore, httpClient, new ThreadPoolExecutor(
                MAX_CONCURRENT_DELIVERIES, MAX_CONCURRENT_DELIVERIES, 0L, TimeUnit.MILLISECONDS,
                // Bound the pending queue so a burst of events cannot grow memory
                // without limit. Saturation rejects the submit, which onAuditEvent
                // catches and logs - delivery is best-effort by design.
                new ArrayBlockingQueue<>(MAX_QUEUED_DELIVERIES),
                new ThreadPoolExecutor.AbortPolicy()));
    }

    WebhookNotifier(WebhookConfigStore webhookConfigStore, HttpClient httpClient,
                    ExecutorService executor) {
        this.webhookConfigStore = webhookConfigStore;
        this.httpClient = httpClient;
        this.executor = executor;
    }

    /**
     * Fans out an audit event to every enabled webhook subscribed to its type.
     * Runs synchronously for the (cheap) subscription query; deliveries are
     * submitted to the bounded executor.
     */
    @EventListener
    public void onAuditEvent(AuditEventRecorded recorded) {
        AuditEvent event = recorded.event();
        try {
            List<WebhookConfig> matching = webhookConfigStore.findByEnabledTrue().stream()
                    .filter(WebhookConfig::isEnabled)
                    .filter(webhook -> webhook.getEventTypes() == null || webhook.getEventTypes().isEmpty()
                            || webhook.getEventTypes().contains(event.getEventType()))
                    .toList();
            for (WebhookConfig webhook : matching) {
                executor.submit(() -> deliver(webhook, event));
            }
        } catch (Exception e) {
            // Notifications are best-effort. A failure to read webhook configs
            // (or a rejected submit during shutdown) must never fail the admin
            // action that triggered the event.
            log.warn("Could not fan out event {} to webhooks", event.getEventType(), e);
        }
    }

    /**
     * POSTs the event payload to one webhook, retrying transient failures.
     * Package-private so tests can drive delivery directly.
     */
    void deliver(WebhookConfig webhook, AuditEvent event) {
        try {
            String payload = toJson(event);
            HttpRequest request = buildRequest(webhook, payload);
            for (int attempt = 1; attempt <= MAX_DELIVERY_ATTEMPTS; attempt++) {
                if (send(request, webhook.getName(), attempt)) {
                    return;
                }
                if (attempt < MAX_DELIVERY_ATTEMPTS && !sleep(RETRY_DELAY_MILLIS)) {
                    return;
                }
            }
        } catch (Exception e) {
            log.error("Could not deliver webhook '{}' for event {}", webhook.getName(), event.getEventType(), e);
        }
    }

    /**
     * Sends one attempt. Returns {@code true} when delivery is settled (success
     * or a permanent failure that retrying cannot fix), {@code false} when the
     * attempt is worth retrying.
     */
    private boolean send(HttpRequest request, String webhookName, int attempt) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            if (statusCode >= 200 && statusCode < 300) {
                return true;
            }
            if (statusCode >= 400 && statusCode < 500 && statusCode != 429) {
                log.warn("Webhook '{}' permanently rejected payload with status {}; not retrying",
                        webhookName, statusCode);
                return true;
            }
            log.warn("Webhook '{}' returned status {} (attempt {}/{})",
                    webhookName, statusCode, attempt, MAX_DELIVERY_ATTEMPTS);
            return false;
        } catch (IOException e) {
            log.warn("Webhook '{}' delivery failed (attempt {}/{})", webhookName, attempt, MAX_DELIVERY_ATTEMPTS, e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    private HttpRequest buildRequest(WebhookConfig webhook, String payload) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(webhook.getUrl()))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload));
        String signature = sign(payload, webhook.getSecret());
        if (signature != null) {
            builder.header("X-Webhook-Signature", signature);
        }
        return builder.build();
    }

    private static String toJson(AuditEvent event) {
        return "{\"eventType\":" + Json.jsonString(event.getEventType())
                + ",\"dbName\":" + Json.jsonString(event.getDbName())
                + ",\"userName\":" + Json.jsonString(event.getUserName())
                + ",\"performedBy\":" + Json.jsonString(event.getPerformedBy())
                + ",\"performedAt\":" + Json.jsonString(event.getPerformedAt().toString()) + "}";
    }

    private static String sign(String payload, String secret) {
        if (secret == null || secret.isBlank()) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Could not sign webhook payload", e);
            return null;
        }
    }

    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}