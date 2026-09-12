package com.pkmprojects.mongodbserver.model;

/**
 * Safe metadata view of {@link ConnectionEndpoint} — no password, safe for
 * JSON, logs, actuator and audit records.
 */
public record PublicConnectionEndpoint(
        String host,
        int port,
        String database,
        String username,
        SslMode sslMode,
        ConnectionMode mode,
        PoolMode poolMode) {
}
