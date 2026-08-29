package com.pkmprojects.mongodbserver.config;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed-window login attempt counter held in-process. Single-instance brute-force
 * protection: the counter lives in JVM memory, so a restart resets every client's
 * window (acceptable for a single-admin control plane - a restart is a rare,
 * admin-driven event). Kept intentionally simple; if the app ever scales to
 * multiple instances behind a load balancer, swap this for a shared store (e.g.
 * Redis {@code INCR}/{@code EXPIRE}) - the {@link LoginRateLimitFilter} contract
 * stays identical.
 *
 * <p>The key space is bounded: once {@value #MAX_KEYS} distinct client keys are
 * tracked, expired windows are pruned on the spot so a brute-force attacker
 * cycling many usernames/IPs cannot grow memory without limit.
 */
@Component
public class LoginRateLimiter {

    private record WindowEntry(int count, Instant windowStart) {
    }

    /**
     * Upper bound on distinct tracked client keys, independent of window size.
     */
    static final int MAX_KEYS = 10_000;

    /**
     * Pruning horizon. Windows differ per call site, so pruning uses a single
     * generous retention: any little-used key older than this is certainly past
     * its window and safe to drop.
     */
    private static final Duration PRUNE_HORIZON = Duration.ofHours(24);

    private final ConcurrentHashMap<String, WindowEntry> attempts = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * Records an attempt for {@code clientKey} and reports whether it stays within
     * {@code maxAttempts} for the current {@code window}.
     */
    public boolean isAllowed(String clientKey, int maxAttempts, Duration window) {
        Instant now = clock.instant();
        WindowEntry updated = attempts.compute(clientKey, (key, entry) -> {
            if (entry == null || !entry.windowStart().plus(window).isAfter(now)) {
                return new WindowEntry(1, now);
            }
            return new WindowEntry(entry.count() + 1, entry.windowStart());
        });
        if (attempts.size() > MAX_KEYS) {
            pruneStale(now);
        }
        return updated.count() <= maxAttempts;
    }

    /**
     * Clears the rate-limit window for {@code clientKey} (e.g. on successful login).
     */
    public void clear(String clientKey) {
        attempts.remove(clientKey);
    }

    java.util.Set<String> snapshotKeys() {
        return java.util.Set.copyOf(attempts.keySet());
    }

    /**
     * Drops every entry whose window has certainly elapsed by now, freeing the map
     * once it exceeds {@value #MAX_KEYS} keys. The linear scan only runs above the
     * cap, never on the hot path.
     */
    private void pruneStale(Instant now) {
        for (Map.Entry<String, WindowEntry> entry : attempts.entrySet()) {
            WindowEntry value = entry.getValue();
            if (value != null && !value.windowStart().plus(PRUNE_HORIZON).isAfter(now)) {
                attempts.remove(entry.getKey(), value);
            }
        }
    }
}
