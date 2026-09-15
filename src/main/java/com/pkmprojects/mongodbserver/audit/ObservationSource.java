package com.pkmprojects.mongodbserver.audit;

/**
 * Native telemetry source that produced one audit event. The manager never sits
 * in the tenant SQL path; every source below is observed externally.
 */
public enum ObservationSource {
    POSTGRES_JSONLOG,
    BRIDGE_SESSION,
    MYSQL_SLOW_LOG,
    MONGO_PROFILER
}
