package com.pkmprojects.mongodbserver.repository;

/**
 * Outcome of a provision-time uniqueness probe against a live engine.
 *
 * <p>PostgreSQL roles and MySQL accounts are server-global: a name requested
 * for a second database must not be allowed to reuse the first tenant's, or the
 * two tenants end up sharing a credential and accumulating each other's grants.
 *
 * <p>The distinction that matters is {@link #UNKNOWN}. A boolean probe cannot
 * tell "nobody has this name" apart from "the probe did not run" -- both are
 * {@code false} -- and collapsing them turns a transient connection failure into
 * an authorization event, because the subsequent create quietly alters whatever
 * it finds. Callers must treat {@link #UNKNOWN} as a reason to stop, not as
 * permission to proceed.
 */
public enum ProbeResult {

    /** The name is not taken. */
    FREE,

    /** The name already exists on the server. */
    OCCUPIED,

    /** The probe could not be completed; its answer must not be trusted. */
    UNKNOWN
}
