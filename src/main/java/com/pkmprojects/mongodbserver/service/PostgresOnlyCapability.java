package com.pkmprojects.mongodbserver.service;

/**
 * Capabilities that only the PostgreSQL engine has: PgBouncer pooled-auth
 * installation and pgvector extensions.
 *
 * <p>These used to be {@code default} members on {@link DatabaseEngine}, where
 * four of the six quietly returned {@code false}/{@code null} and the other two
 * threw. That let {@code engine.installPooledAuth(...)} type-check on a Mongo
 * engine and fail at runtime, with correctness resting on the caller having
 * already checked {@code engineType == POSTGRES}. Every one of those guards
 * exists and works today, so this is not closing a live bug -- it is making the
 * compiler hold the line so the guards cannot drift out of step with the
 * interface.
 *
 * <p>Callers reach it either through the concrete {@link PostgresDatabaseEngine}
 * or by narrowing to this type once they have established the engine is
 * PostgreSQL. See {@code ProvisioningService} for both shapes.
 */
public interface PostgresOnlyCapability {

    /** Installs the least-privilege auth role and per-database lookup function. */
    void installPooledAuth(String dbName);

    /** Whether the pooled-auth lookup is already installed. */
    boolean isPooledAuthInstalled(String dbName);

    /** Whether the server has pgvector available at all. */
    boolean isVectorAvailable();

    /** Whether pgvector is enabled on this database. */
    boolean isVectorEnabled(String dbName);

    /** Enables the vector extension on this database. */
    void enableVector(String dbName);

    /** Installed extension version, or {@code null} when not enabled. */
    String vectorVersion(String dbName);
}
