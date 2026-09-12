package com.pkmprojects.mongodbserver.model;

/**
 * Private Docker/service topology. Never exposed via API/UI/docs.
 * e.g. postgres:5432, pgbouncer:6432.
 */
public record InternalDatabaseEndpoint(String host, int port, String service) {
    public static InternalDatabaseEndpoint postgres(String host, int port) {
        return new InternalDatabaseEndpoint(host, port, "postgres");
    }

    public static InternalDatabaseEndpoint pgbouncer(String host, int port) {
        return new InternalDatabaseEndpoint(host, port, "pgbouncer");
    }
}
