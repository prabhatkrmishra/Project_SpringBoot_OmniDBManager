package com.pkmprojects.mongodbserver.model;

/**
 * Both application paths for one managed database. Same identity +
 * credentials, different route. {@code pooled} is null when the database
 * was provisioned direct-only.
 */
public record DatabaseConnections(ConnectionEndpoint direct, ConnectionEndpoint pooled) {
    public DatabaseConnections {
        if (direct == null) throw new IllegalArgumentException("direct endpoint is required");
        if (direct.mode() != ConnectionMode.DIRECT) throw new IllegalArgumentException("direct.mode must be DIRECT");
        if (pooled != null && pooled.mode() != ConnectionMode.POOLED)
            throw new IllegalArgumentException("pooled.mode must be POOLED");
    }

    public boolean isPooledEnabled() {
        return pooled != null;
    }

    /** Compatibility mapping for legacy {@code pooled:boolean} metadata. */
    public static DatabaseConnections fromLegacy(boolean pooled, ConnectionEndpoint direct, ConnectionEndpoint pooledEndpoint) {
        if (!pooled) return new DatabaseConnections(direct, null);
        return new DatabaseConnections(direct, pooledEndpoint);
    }
}
