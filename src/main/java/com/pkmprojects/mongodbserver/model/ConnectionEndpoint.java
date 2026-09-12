package com.pkmprojects.mongodbserver.model;

/**
 * Structured public endpoint for one connection path. The password lives
 * only in memory for string building and is never persisted; use
 * {@link PublicConnectionEndpoint} for metadata APIs/logs.
 */
public record ConnectionEndpoint(
        String host,
        int port,
        String database,
        String username,
        String password,
        SslMode sslMode,
        ConnectionMode mode,
        PoolMode poolMode) {
    public PublicConnectionEndpoint withoutSecret() {
        return new PublicConnectionEndpoint(host, port, database, username, sslMode, mode, poolMode);
    }
}
