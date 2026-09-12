package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.config.PgbouncerProperties;
import com.pkmprojects.mongodbserver.dto.PgbouncerSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

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
    // Same proxy-TLS switch as PgbouncerAdminService (S-06): passthrough proxy
    // => pooler requires client TLS => stats polling must use sslmode=require.
    private volatile com.pkmprojects.mongodbserver.config.DatabaseProxyProperties proxyProperties;

    @Autowired(required = false)
    public void setProxyProperties(com.pkmprojects.mongodbserver.config.DatabaseProxyProperties proxyProperties) {
        this.proxyProperties = proxyProperties;
    }
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
        // Never return null — DriverManager treats null inconsistently across drivers;
        // empty lets auth fail cleanly as unreachable instead of NPE.
        return "";
    }

    public PgbouncerSnapshot getSnapshot() {
        List<PgbouncerSnapshot.Pool> pools = new ArrayList<>();
        PgbouncerSnapshot.Stats stats = null;
        String status;

        // Raw protocol console client (see PgbouncerConsoleClient): pgjdbc's
        // connect-time SET probe dies on the console db with "SET failed".
        // Column positions follow the 1.24 admin.c layouts:
        //   SHOW POOLS: database(0) user(1) cl_active(2) cl_waiting(3) ...
        //     sv_active(6) sv_idle(9) maxwait-seconds(13) pool_mode(15)
        //   SHOW STATS: database(0) total_query_count(3)
        //     total_query_time(7) total_wait_time(8)
        try (PgbouncerConsoleClient c = openAdminConnection()) {

            // SHOW POOLS
            for (String[] row : c.query("SHOW POOLS").rows()) {
                String db = row[0];
                String poolMode = row[15];
                int clActive = parseInt(row[2]);
                int clWaiting = parseInt(row[3]);
                int svActive = parseInt(row[6]);
                int svIdle = parseInt(row[9]);
                // pgbouncer: maxwait is in seconds
                double maxWait = parseDouble(row[13]);
                pools.add(new PgbouncerSnapshot.Pool(db, poolMode, clActive, clWaiting, svActive, svIdle, maxWait));
            }

            // SHOW STATS — aggregate across databases
            long totalQueries = 0;
            double totalQueryTime = 0;
            long totalWaitTime = 0;
            int rows = 0;
            for (String[] row : c.query("SHOW STATS").rows()) {
                long q = parseLong(row[3]);
                double qTime = parseDouble(row[7]);
                long wait = parseLong(row[8]);
                totalQueries += q;
                totalQueryTime += qTime;
                totalWaitTime += wait;
                rows++;
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
        try (PgbouncerConsoleClient c = openAdminConnection()) {
            var result = c.query("SHOW POOLS");
            if (result.rows().isEmpty() && poolsEmptyIsOk()) return;
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

    /** sslmode for pooler stats connections: require iff the TLS-passthrough proxy is configured. */
    String poolerSslMode() {
        return proxyProperties != null && proxyProperties.isConfigured() ? "require" : "disable";
    }

    private PgbouncerConsoleClient openAdminConnection() throws Exception {
        // pgbouncer virtual database — stats user, raw protocol (see above).
        return PgbouncerConsoleClient.connect("127.0.0.1", properties.port(),
                properties.statsUser(), resolveStatsPassword(),
                poolerSslMode().equals("require"), 2000, 3000);
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0L; }
    }

    private static double parseDouble(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0; }
    }
}
