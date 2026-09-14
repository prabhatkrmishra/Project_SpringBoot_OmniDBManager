package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.model.AuditEvent;
import com.pkmprojects.mongodbserver.model.AuditEventRecorded;
import com.pkmprojects.mongodbserver.model.WebhookConfig;
import com.pkmprojects.mongodbserver.model.WebhookDeliveryAttempt;
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
import java.time.Instant;
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
 *
 * <p>Every settled attempt is also recorded on the bounded
 * {@link WebhookDeliveryTrail} (no secrets, no bodies) so failures are
 * visible on the webhooks page instead of only in server logs.</p>
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
    private final WebhookDeliveryTrail deliveryTrail;

    @Autowired
    public WebhookNotifier(WebhookConfigStore webhookConfigStore, HttpClient httpClient,
                           @Autowired(required = false) WebhookDeliveryTrail deliveryTrail) {
        this(webhookConfigStore, httpClient, new ThreadPoolExecutor(
                MAX_CONCURRENT_DELIVERIES, MAX_CONCURRENT_DELIVERIES, 0L, TimeUnit.MILLISECONDS,
                // Bound the pending queue so a burst of events cannot grow memory
                // without limit. Saturation rejects the submit, which onAuditEvent
                // catches and logs - delivery is best-effort by design.
                new ArrayBlockingQueue<>(MAX_QUEUED_DELIVERIES),
                new ThreadPoolExecutor.AbortPolicy()), deliveryTrail);
    }

    WebhookNotifier(WebhookConfigStore webhookConfigStore, HttpClient httpClient,
                    ExecutorService executor) {
        this(webhookConfigStore, httpClient, executor, null);
    }

    WebhookNotifier(WebhookConfigStore webhookConfigStore, HttpClient httpClient,
                    ExecutorService executor, WebhookDeliveryTrail deliveryTrail) {
        this.webhookConfigStore = webhookConfigStore;
        this.httpClient = httpClient;
        this.executor = executor;
        this.deliveryTrail = deliveryTrail;
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
            // Re-check the target at send time, not just at creation: a webhook
            // URL that was public when configured could later resolve to a
            // private/internal address (DNS rebinding), which would otherwise
            // turn delivery into an SSRF probe of the internal network.
            String host = URI.create(webhook.getUrl()).getHost();
            if (host != null && WebhookService.isBlockedDeliveryHost(host)) {
                log.warn("Skipping delivery to webhook '{}': target host '{}' is blocked (private/internal or reserved)",
                        webhook.getName(), host);
                trail(webhook, event, 1, WebhookDeliveryAttempt.DeliveryResult.FAILED, null, "blocked-host", true);
                return;
            }
            String payload = toJson(event);
            HttpRequest request = buildRequest(webhook, payload);
            for (int attempt = 1; attempt <= MAX_DELIVERY_ATTEMPTS; attempt++) {
                if (send(request, webhook, event, attempt)) {
                    return;
                }
                if (attempt < MAX_DELIVERY_ATTEMPTS && !sleep(RETRY_DELAY_MILLIS)) {
                    // Interrupted between attempts: the last send() already
                    // recorded its RETRYING attempt; nothing more to record.
                    return;
                }
            }
            // Exhausted all attempts: the final send() already recorded its
            // FAILED attempt with finalAttempt=true. No extra record here —
            // one record per attempt, never a synthetic summary.
        } catch (Exception e) {
            log.error("Could not deliver webhook '{}' for event {}", webhook.getName(), event.getEventType(), e);
            trail(webhook, event, 1, WebhookDeliveryAttempt.DeliveryResult.FAILED, null, "error", true);
        }
    }

    /**
     * Sends one attempt. Returns {@code true} when delivery is settled (success
     * or a permanent failure that retrying cannot fix), {@code false} when the
     * attempt is worth retrying.
     */
    private boolean send(HttpRequest request, WebhookConfig webhook, AuditEvent event, int attempt) {
        String webhookName = webhook.getName();
        boolean last = attempt >= MAX_DELIVERY_ATTEMPTS;
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            if (statusCode >= 200 && statusCode < 300) {
                trail(webhook, event, attempt, WebhookDeliveryAttempt.DeliveryResult.DELIVERED,
                        statusCode, null, true);
                return true;
            }
            if (statusCode >= 400 && statusCode < 500 && statusCode != 429) {
                log.warn("Webhook '{}' permanently rejected payload with status {}; not retrying",
                        webhookName, statusCode);
                trail(webhook, event, attempt, WebhookDeliveryAttempt.DeliveryResult.FAILED,
                        statusCode, "client-error", true);
                return true;
            }
            log.warn("Webhook '{}' returned status {} (attempt {}/{})",
                    webhookName, statusCode, attempt, MAX_DELIVERY_ATTEMPTS);
            trail(webhook, event, attempt,
                    last ? WebhookDeliveryAttempt.DeliveryResult.FAILED
                            : WebhookDeliveryAttempt.DeliveryResult.RETRYING,
                    statusCode, "server-error", last);
            return false;
        } catch (IOException e) {
            log.warn("Webhook '{}' delivery failed (attempt {}/{})", webhookName, attempt, MAX_DELIVERY_ATTEMPTS, e);
            trail(webhook, event, attempt,
                    last ? WebhookDeliveryAttempt.DeliveryResult.FAILED
                            : WebhookDeliveryAttempt.DeliveryResult.RETRYING,
                    null, "io-error", last);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            trail(webhook, event, attempt, WebhookDeliveryAttempt.DeliveryResult.FAILED,
                    null, "interrupted", true);
            return true;
        }
    }

    private void trail(WebhookConfig webhook, AuditEvent event, int attempt,
                       WebhookDeliveryAttempt.DeliveryResult result, Integer httpStatus,
                       String failureCategory, boolean finalAttempt) {
        if (deliveryTrail == null) return;
        try {
            deliveryTrail.record(new WebhookDeliveryAttempt(
                    webhook.getId() != null ? webhook.getId() : webhook.getName(),
                    webhook.getName(), event.getEventType(), Instant.now(),
                    attempt, result, httpStatus, failureCategory, finalAttempt));
        } catch (RuntimeException e) {
            // Telemetry must never break delivery.
            log.debug("Could not record webhook delivery attempt", e);
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
