package com.pkmprojects.mongodbserver.config;

import com.pkmprojects.mongodbserver.repository.MongoDatabaseRepository;
import com.pkmprojects.mongodbserver.repository.MysqlDatabaseRepository;
import com.pkmprojects.mongodbserver.repository.PostgresDatabaseRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deterministic readiness signal for the control plane.
 *
 * <p>Actuator health groups fail startup on unknown contributor names, so a
 * static {@code include: readinessState,db,mongo,...} list cannot work when
 * engines are independently optional. This single unconditional indicator
 * (bean name {@code controlPlane}, always resolvable) checks exactly the
 * control-plane stores that are configured in this deployment and reports
 * DOWN naming the failing store. Tenant/data-plane health is deliberately
 * NOT an input. Details stay behind the ADMIN-gated full health endpoint
 * ({@code show-details: never} for anonymous probes).
 *
 * <p>Bounded by construction: each store check runs on its own thread with
 * a hard 1.5s cap (a dead store must degrade readiness, never wedge the
 * probe past the orchestrator's timeout). Maintenance connections only,
 * never tenant databases.
 */
@Component("controlPlane")
public class ControlPlaneReadinessIndicator implements HealthIndicator {

    /** Per-store probe cap: the whole indicator answers well under 5s. */
    static final long PROBE_TIMEOUT_MS = 1500;

    private final PostgresDatabaseRepository postgresRepository;
    private final MysqlDatabaseRepository mysqlRepository;
    private final MongoDatabaseRepository mongoRepository;

    public ControlPlaneReadinessIndicator(
            @Autowired(required = false) PostgresDatabaseRepository postgresRepository,
            @Autowired(required = false) MysqlDatabaseRepository mysqlRepository,
            @Autowired(required = false) MongoDatabaseRepository mongoRepository) {
        this.postgresRepository = postgresRepository;
        this.mysqlRepository = mysqlRepository;
        this.mongoRepository = mongoRepository;
    }

    @Override
    public Health health() {
        Map<String, String> checked = new LinkedHashMap<>();
        Map<String, String> failing = new LinkedHashMap<>();
        ping("postgres", postgresRepository != null ? postgresRepository::ping : null, checked, failing);
        ping("mysql", mysqlRepository != null ? mysqlRepository::ping : null, checked, failing);
        ping("mongo", mongoRepository != null ? mongoRepository::ping : null, checked, failing);
        if (!failing.isEmpty()) {
            return Health.down().withDetails(Map.of("failing", String.join(",", failing.keySet()))).build();
        }
        return Health.up().withDetails(Map.of("stores", String.join(",", checked.keySet()))).build();
    }

    private static void ping(String name, Runnable ping,
                             Map<String, String> checked, Map<String, String> failing) {
        if (ping == null) {
            return;
        }
        checked.put(name, "ok");
        // A dead store's own driver timeout (e.g. 30s+ Hikari/mongo waits)
        // must not wedge the probe: isolate each ping on a daemon thread
        // with a hard cap and treat timeout as DOWN for that store.
        final boolean[] done = {false};
        final Exception[] err = {null};
        Thread worker = new Thread(() -> {
            try {
                ping.run();
                synchronized (done) {
                    done[0] = true;
                    done.notifyAll();
                }
            } catch (Exception e) {
                synchronized (done) {
                    err[0] = e;
                    done.notifyAll();
                }
            }
        });
        worker.setDaemon(true);
        worker.start();
        synchronized (done) {
            long deadline = System.currentTimeMillis() + PROBE_TIMEOUT_MS;
            while (!done[0] && err[0] == null) {
                long wait = deadline - System.currentTimeMillis();
                if (wait <= 0) break;
                try {
                    done.wait(wait);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (err[0] != null || !done[0]) {
                failing.put(name, err[0] != null ? err[0].getClass().getSimpleName() : "timeout");
            }
        }
    }
}
