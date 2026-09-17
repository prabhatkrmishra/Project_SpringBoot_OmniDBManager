package com.pkmprojects.mongodbserver.audit.ingest;

import java.time.Instant;

/**
 * Structured session-start/session-context record emitted by the Go bridge.
 *
 * <p>Allowed fields only: event timestamp, bridge connection/session id
 * (long operator id), short correlation SID carried downstream as
 * {@code application_name=omnidb:<sid>}, client source IP/port,
 * StartupMessage username, database, mode, pool profile, downstream route,
 * backend PID/identity where safely known, and session lifecycle
 * timestamps. No passwords, no SCRAM material, no raw StartupMessage, no
 * SQL. The SID is opaque random ([a-z0-9]{8,32}); it carries no IP, user,
 * database, or secret.</p>
 */
public record BridgeSessionEvent(
        Instant eventAt,
        String sessionId,
        String clientIp,
        Integer clientPort,
        String username,
        String database,
        String mode,
        String poolProfile,
        String downstreamRoute,
        Integer backendPid,
        Instant connectedAt,
        Instant closedAt,
        String bridgeSessionId) {

    public BridgeSessionEvent {
        if (eventAt == null) throw new IllegalArgumentException("eventAt is required");
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId is required");
    }

    /**
     * Backwards-compatible 12-arg constructor for pre-SID callers/tests.
     * The bridge correlation SID is null (uncorrelated legacy session).
     */
    public BridgeSessionEvent(
            Instant eventAt,
            String sessionId,
            String clientIp,
            Integer clientPort,
            String username,
            String database,
            String mode,
            String poolProfile,
            String downstreamRoute,
            Integer backendPid,
            Instant connectedAt,
            Instant closedAt) {
        this(eventAt, sessionId, clientIp, clientPort, username, database, mode,
                poolProfile, downstreamRoute, backendPid, connectedAt, closedAt, null);
    }

    /** True for pooled legs where per-statement IP attribution is inferred. */
    public boolean pooled() {
        return "pooled".equalsIgnoreCase(mode);
    }
}
