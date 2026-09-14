package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.model.WebhookDeliveryAttempt;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Bounded in-memory trail of webhook delivery attempts.
 *
 * <p>Design notes: delivery attempts are operational telemetry, not audit
 * records — they live in a fixed-size deque (newest first, oldest evicted)
 * shared by all webhooks, so retention is deliberately bounded and needs no
 * migration, no TTL index, and no per-webhook bookkeeping. ADMIN-only
 * readers get a copy; writers never block delivery.</p>
 */
@Service
public class WebhookDeliveryTrail {

    /** Maximum retained attempts across all webhooks. */
    static final int MAX_RETAINED = 100;

    private final Deque<WebhookDeliveryAttempt> recent = new ArrayDeque<>();

    /** Records one attempt; evicts the oldest when the bound is reached. */
    public synchronized void record(WebhookDeliveryAttempt attempt) {
        recent.addFirst(attempt);
        while (recent.size() > MAX_RETAINED) {
            recent.removeLast();
        }
    }

    /** Newest-first snapshot, safe to render. */
    public synchronized List<WebhookDeliveryAttempt> recent() {
        return List.copyOf(recent);
    }

    /** Newest-first attempts for one webhook. */
    public synchronized List<WebhookDeliveryAttempt> recentFor(String webhookId) {
        return recent.stream()
                .filter(a -> a.webhookId().equals(webhookId))
                .toList();
    }

    /** Latest attempt overall, if any. */
    public synchronized Optional<WebhookDeliveryAttempt> latest() {
        return Optional.ofNullable(recent.peekFirst());
    }

    /** Latest attempt for one webhook, if any. */
    public synchronized Optional<WebhookDeliveryAttempt> latestFor(String webhookId) {
        return recent.stream().filter(a -> a.webhookId().equals(webhookId)).findFirst();
    }

    synchronized void clear() {
        recent.clear();
    }
}
