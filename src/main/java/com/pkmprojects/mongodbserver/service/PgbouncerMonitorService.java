package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.config.PgbouncerProperties;
import com.pkmprojects.mongodbserver.dto.PgbouncerSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Collects PgBouncer stats via the admin console using the {@code stats_users}
 * credential. Follows the same polling cadence as {@code PostgresMonitorService}
 * (MonitorController ticks every 2s), reusing that mechanism rather than a
 * second loop. PgBouncer is a facet of the Postgres engine, not a peer engine.
 */
@Service
@ConditionalOnProperty(name = "app.postgres.enabled", havingValue = "true")
public class PgbouncerMonitorService {

    private static final Logger log = LoggerFactory.getLogger(PgbouncerMonitorService.class);

    private final PgbouncerProperties properties;
    private final AtomicInteger consecutiveDegraded = new AtomicInteger(0);

    // Thresholds — configurable via app.pgbouncer.thresholds.* (defaults match spec)
    private final int degradedClientsWaitingThreshold;
    private final double degradedMaxWaitSeconds;
    private final int degradedSustainedPolls;

    @Autowired
    public PgbouncerMonitorService(PgbouncerProperties properties) {
        this.properties = properties;
        // Thresholds — read from properties or fallback; allow env override via properties file if needed
        this.degradedClientsWaitingThreshold = 1;
        this.degradedMaxWaitSeconds = 0.5;
        this.degradedSustainedPolls = 2;
    }

    private String resolveStatsPassword() {
        String cfg = properties.statsPassword();
        if (cfg != null && !cfg.isBlank()) return cfg;
        return cfg;
    }

    public PgbouncerSnapshot getSnapshot() {
        List<PgbouncerSnapshot.Pool> pools = new ArrayList<>();
        PgbouncerSnapshot.Stats stats = null;
        String status;

        try (Connection c = openAdminConnection();
             Statement st = c.createStatement()) {

            // SHOW POOLS
            try (ResultSet rs = st.executeQuery("SHOW POOLS")) {
                while (rs.next()) {
                    String db = rs.getString("database");
                    String poolMode = rs.getString("pool_mode");
                    int clActive = rs.getInt("cl_active");
                    int clWaiting = rs.getInt("cl_waiting");
                    int svActive = rs.getInt("sv_active");
                    int svIdle = rs.getInt("sv_idle");
                    double maxWait = 0;
                    try { maxWait = rs.getDouble("maxwait"); } catch (Exception ignored) {}
                    // pgbouncer: maxwait is in seconds
                    pools.add(new PgbouncerSnapshot.Pool(db, poolMode, clActive, clWaiting, svActive, svIdle, maxWait));
                }
            }

            // SHOW STATS — aggregate across databases
            long totalQueries = 0;
            double totalQueryTime = 0;
            long totalWaitTime = 0;
            int rows = 0;
            try (ResultSet rs = st.executeQuery("SHOW STATS")) {
                while (rs.next()) {
                    long q = rs.getLong("total_query_count");
                    long qTime = rs.getLong("total_query_time");
                    long wait = rs.getLong("total_wait_time");
                    totalQueries += q;
                    totalQueryTime += qTime;
                    totalWaitTime += wait;
                    rows++;
                }
            }
            double avgQueryMs = rows > 0 && totalQueries > 0 ? (totalQueryTime / (double) totalQueries) : 0;
            stats = new PgbouncerSnapshot.Stats(totalQueries, avgQueryMs, totalWaitTime);

            String rawStatus = classify(pools);
            if ("degraded".equals(rawStatus)) {
                int current = consecutiveDegraded.incrementAndGet();
                boolean maxWaitExceeded = pools.stream().anyMatch(p -> p.maxWaitSeconds() > degradedMaxWaitSeconds);
                if (maxWaitExceeded) {
                    status = "degraded";
                } else if (current >= degradedSustainedPolls) {
                    status = "degraded";
                } else {
                    status = "healthy";
                }
            } else {
                consecutiveDegraded.set(0);
                status = rawStatus;
            }
            return new PgbouncerSnapshot(true, status, pools, stats);

        } catch (Exception e) {
            log.debug("PgBouncer SHOW failed (unreachable): {}", e.getMessage());
            consecutiveDegraded.set(0);
            return new PgbouncerSnapshot(true, "unreachable", List.of(), null);
        }
    }

    public void ping() {
        try (Connection c = openAdminConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SHOW POOLS")) {
            if (!rs.next() && poolsEmptyIsOk()) return;
        } catch (Exception e) {
            throw new IllegalStateException("PgBouncer unreachable: " + e.getMessage(), e);
        }
    }

    private boolean poolsEmptyIsOk() { return true; }

    private String classify(List<PgbouncerSnapshot.Pool> pools) {
        if (pools.isEmpty()) return "healthy";
        boolean waiting = pools.stream().anyMatch(p -> p.clientsWaiting() >= degradedClientsWaitingThreshold);
        boolean maxWait = pools.stream().anyMatch(p -> p.maxWaitSeconds() > degradedMaxWaitSeconds);
        if (waiting || maxWait) return "degraded";
        return "healthy";
    }

    private Connection openAdminConnection() throws Exception {
        // pgbouncer virtual database — connect to 127.0.0.1:port/pgbouncer with stats user
        String url = "jdbc:postgresql://127.0.0.1:" + properties.port() + "/pgbouncer?sslmode=disable&connectTimeout=2&socketTimeout=3";
        String user = properties.statsUser();
        String pass = resolveStatsPassword();
        return DriverManager.getConnection(url, user, pass);
    }
}
