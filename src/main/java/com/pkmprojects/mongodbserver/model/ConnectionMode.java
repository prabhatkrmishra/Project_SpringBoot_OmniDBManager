package com.pkmprojects.mongodbserver.model;

/**
 * How a managed database is reached from the outside.
 * DIRECT = full PostgreSQL session semantics (migrations/admin).
 * POOLED = PgBouncer transaction pooling (app/workers).
 */
public enum ConnectionMode {
    DIRECT,
    POOLED
}
