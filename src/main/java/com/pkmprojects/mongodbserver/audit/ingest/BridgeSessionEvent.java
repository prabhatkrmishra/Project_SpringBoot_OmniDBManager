package com.pkmprojects.mongodbserver.audit.ingest;

import java.time.Instant;

/**
 * Structured session-start/session-context record emitted by the Go bridge.
 *
 * <p>Allowed fields only: event timestamp, bridge connection/session id,
 * client source IP/port, StartupMessage username, database, mode, pool
 * profile, downstream route, backend PID/identity where safely known, and
 * session lifecycle timestamps. No passwords, no SCRAM material, no raw
 * StartupMessage, no SQL.</p>
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
        Instant closedAt) {

    public BridgeSessionEvent {
        if (eventAt == null) throw new IllegalArgumentException("eventAt is required");
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId is required");
    }

    /** True for pooled legs where per-statement IP attribution is inferred. */
    public boolean pooled() {
        return "pooled".equalsIgnoreCase(mode);
    }
}
