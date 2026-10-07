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
     * Frees space once the map exceeds {@value #MAX_KEYS} keys: first drop
     * everything whose window has certainly elapsed, then, if that was not
     * enough, evict oldest-first until it is.
     *
     * <p>The second half is what makes {@value #MAX_KEYS} an actual cap. Without
     * it, {@code MAX_KEYS} was only a trigger: the stale pass removes nothing
     * while an attacker keeps every key fresh, so an attacker cycling more than
     * {@value #MAX_KEYS} distinct usernames within {@link #PRUNE_HORIZON} drove a
     * full linear scan on <em>every</em> subsequent login request, pre-auth. The
     * key is client IP plus submitted username, so username variation alone is
     * enough to mint unlimited keys.
     *
     * <p>Only runs above the cap, never on the hot path.
     */
    private void pruneStale(Instant now) {
        for (Map.Entry<String, WindowEntry> entry : attempts.entrySet()) {
            WindowEntry value = entry.getValue();
            if (value != null && !value.windowStart().plus(PRUNE_HORIZON).isAfter(now)) {
                attempts.remove(entry.getKey(), value);
            }
        }
        while (attempts.size() > MAX_KEYS) {
            String oldestKey = null;
            Instant oldest = null;
            for (Map.Entry<String, WindowEntry> entry : attempts.entrySet()) {
                Instant start = entry.getValue() == null ? null : entry.getValue().windowStart();
                if (start != null && (oldest == null || start.isBefore(oldest))) {
                    oldest = start;
                    oldestKey = entry.getKey();
                }
            }
            if (oldestKey == null || attempts.remove(oldestKey) == null) {
                break;   // nothing removable (all entries null); avoid spinning
            }
        }
    }
}
