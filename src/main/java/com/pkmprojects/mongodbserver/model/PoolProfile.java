package com.pkmprojects.mongodbserver.model;

import java.util.List;

/**
 * Named connection profiles for pooled PostgreSQL connections.
 *
 * <p>Profile selection is per CLIENT CONNECTION; profile configuration is per
 * PGBOUNCER INSTANCE. The client selects a named policy and never controls
 * raw PgBouncer configuration.
 *
 * <p>Phase 1 supports exactly {@code standard} (existing pooler, zero behavior
 * change) and {@code high_concurrency} (dedicated pooler, transaction pooling
 * with larger but explicitly capped per-db sizing). Both are
 * {@link PoolMode#TRANSACTION}. There is intentionally no session profile and no {@code ManagedDatabase} profile field — profile is a
 * connection-policy selector, not a database property. Bare
 * {@code mode=pooled} strings mean {@code standard} for backwards
 * compatibility.
 */
public enum PoolProfile {
    STANDARD("standard"),
    HIGH_CONCURRENCY("high_concurrency");

    private final String id;

    PoolProfile(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static final List<PoolProfile> ALL = List.of(values());

    public static PoolProfile parse(String id) {
        for (PoolProfile p : values()) {
            if (p.id.equals(id)) return p;
        }
        throw new IllegalArgumentException("unknown pool profile: " + id);
    }

    public static boolean isSupported(String id) {
        for (PoolProfile p : values()) {
            if (p.id.equals(id)) return true;
        }
        return false;
    }
}
