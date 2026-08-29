package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.service.HealthService;
import com.pkmprojects.mongodbserver.service.ProvisioningService;
import com.pkmprojects.mongodbserver.store.AuditStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Dashboard: database list + recent admin activity.
 * Degrades gracefully when PG/MySQL are down — never blocks >3s per engine (Phase 5).
 */
@Controller
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);
    private static final long LIST_TIMEOUT_SECONDS = 3;

    private final ProvisioningService provisioningService;
    private final AuditStore auditStore;
    private final HealthService healthService;

    public DashboardController(ProvisioningService provisioningService, AuditStore auditStore,
                               HealthService healthService) {
        this.provisioningService = provisioningService;
        this.auditStore = auditStore;
        this.healthService = healthService;
    }

    /**
     * Renders the dashboard: database list and recent activity.
     */
    @GetMapping("/")
    public String dashboard(Model model) {
        var health = healthService.getHealth();

        // Only list databases for reachable engines — skip hung PG/MySQL entirely
        var mongoDbs = listWithTimeout(health.mongoEnabled() && health.mongoReachable(),
                com.pkmprojects.mongodbserver.model.DatabaseEngineType.MONGO);
        model.addAttribute("databases", mongoDbs);
        model.addAttribute("mongoDatabases", mongoDbs);
        model.addAttribute("mongoCount", mongoDbs.size());

        var pgDbs = listWithTimeout(health.postgresEnabled() && health.postgresReachable(),
                com.pkmprojects.mongodbserver.model.DatabaseEngineType.POSTGRES);
        model.addAttribute("postgresDatabases", pgDbs);
        model.addAttribute("postgresCount", pgDbs.size());

        var mysqlDbs = listWithTimeout(health.mysqlEnabled() && health.mysqlReachable(),
                com.pkmprojects.mongodbserver.model.DatabaseEngineType.MYSQL);
        model.addAttribute("mysqlDatabases", mysqlDbs);
        model.addAttribute("mysqlCount", mysqlDbs.size());

        model.addAttribute("mongoEnabled", health.mongoEnabled());
        model.addAttribute("mongoReachable", health.mongoReachable());
        model.addAttribute("postgresReachable", health.postgresReachable());
        model.addAttribute("postgresEnabled", health.postgresEnabled());
        model.addAttribute("mysqlReachable", health.mysqlReachable());
        model.addAttribute("mysqlEnabled", health.mysqlEnabled());
        model.addAttribute("recentActivity", auditStore.findTop10ByOrderByPerformedAtDesc());
        model.addAttribute("engine", null);
        return "index";
    }

    private List<com.pkmprojects.mongodbserver.dto.DatabaseInfo> listWithTimeout(boolean shouldList,
                                                                                 com.pkmprojects.mongodbserver.model.DatabaseEngineType engine) {
        if (!shouldList) return List.of();
        try {
            return CompletableFuture.supplyAsync(() -> provisioningService.listDatabases(engine))
                    .orTimeout(LIST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .join();
        } catch (Exception e) {
            log.warn("Timed out listing {} databases for dashboard", engine, e);
            return List.of();
        }
    }
}
