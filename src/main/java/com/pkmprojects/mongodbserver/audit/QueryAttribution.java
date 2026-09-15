package com.pkmprojects.mongodbserver.audit;

/**
 * How strongly the recorded client identity can be trusted for one audit event.
 *
 * <p>Direct engine paths and database-native identity fields are authoritative.
 * PostgreSQL statements observed downstream of PgBouncer transaction pooling are
 * inferred: the bridge ingress context (client IP, provisioned user, database)
 * is preserved, but per-statement backend attribution cannot be proven without
 * changing the data path.</p>
 */
public enum QueryAttribution {
    AUTHORITATIVE,
    INFERRED
}
