package com.pkmprojects.mongodbserver.model;

import java.time.Instant;

/**
 * One observable delivery attempt for a webhook endpoint.
 *
 * <p>Kept deliberately free of secrets: no webhook secret, no
 * {@code Authorization} header, no request body — only the webhook id, the
 * event identity, the attempt number, the outcome, and the HTTP status where
 * one exists. Retained in a bounded in-memory ring (see
 * {@code WebhookDeliveryTrail}) so the trail cannot grow without limit; it
 * is an operational view, not an audit log.</p>
 */
public record WebhookDeliveryAttempt(
        String webhookId,
        String webhookName,
        String eventType,
        Instant attemptedAt,
        int attemptNumber,
        DeliveryResult result,
        Integer httpStatus,
        String failureCategory,
        boolean finalAttempt) {

    /** Compact delivery lifecycle for the trail. */
    public enum DeliveryResult {
        /** 2xx — settled. */
        DELIVERED,
        /** Settled without retry: 4xx (except 429) or blocked host. */
        FAILED,
        /** 5xx / 429 / IO failure with attempts remaining. */
        RETRYING
    }

    public WebhookDeliveryAttempt {
        if (webhookId == null || webhookId.isBlank()) throw new IllegalArgumentException("webhookId is required");
        if (eventType == null || eventType.isBlank()) throw new IllegalArgumentException("eventType is required");
        if (attemptedAt == null) throw new IllegalArgumentException("attemptedAt is required");
        if (attemptNumber < 1) throw new IllegalArgumentException("attemptNumber must be >= 1");
        if (result == null) throw new IllegalArgumentException("result is required");
    }
}
