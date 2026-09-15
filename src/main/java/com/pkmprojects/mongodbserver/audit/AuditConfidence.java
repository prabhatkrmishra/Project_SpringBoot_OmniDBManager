package com.pkmprojects.mongodbserver.audit;

/**
 * Operator-facing confidence for one audit event. Derived from the observation
 * source and the attribution (see {@link QueryAttribution}).
 */
public enum AuditConfidence {
    HIGH,
    MEDIUM,
    LOW
}
