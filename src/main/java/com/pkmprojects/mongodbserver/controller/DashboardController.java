package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.repository.AuditLogRepository;
import com.pkmprojects.mongodbserver.service.HealthService;
import com.pkmprojects.mongodbserver.service.ProvisioningService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Dashboard: database list + recent admin activity.
 */
@Controller
public class DashboardController {

    private final ProvisioningService provisioningService;
    private final AuditLogRepository auditLogRepository;
    private final HealthService healthService;

    public DashboardController(ProvisioningService provisioningService, AuditLogRepository auditLogRepository,
                               HealthService healthService) {
        this.provisioningService = provisioningService;
        this.auditLogRepository = auditLogRepository;
        this.healthService = healthService;
    }

    /**
     * Renders the dashboard: database list and recent activity.
     */
    @GetMapping("/")
    public String dashboard(Model model) {
        var mongoDbs = java.util.List.<com.pkmprojects.mongodbserver.dto.DatabaseInfo>of();
        try {
            mongoDbs = provisioningService.listDatabases(com.pkmprojects.mongodbserver.model.DatabaseEngineType.MONGO);
        } catch (Exception e) {
            // Mongo down — dashboard still renders with empty list and unreachable dot
        }
        model.addAttribute("databases", mongoDbs);
        model.addAttribute("mongoDatabases", mongoDbs);
        model.addAttribute("mongoCount", mongoDbs.size());
        try {
            var pgDbs = provisioningService.listDatabases(com.pkmprojects.mongodbserver.model.DatabaseEngineType.POSTGRES);
            model.addAttribute("postgresDatabases", pgDbs);
            model.addAttribute("postgresCount", pgDbs.size());
        } catch (Exception e) {
            model.addAttribute("postgresDatabases", java.util.List.of());
            model.addAttribute("postgresCount", 0);
        }
        var health = healthService.getHealth();
        model.addAttribute("mongoReachable", health.mongoReachable());
        model.addAttribute("postgresReachable", health.postgresReachable());
        model.addAttribute("postgresEnabled", health.postgresEnabled());
        model.addAttribute("recentActivity", auditLogRepository.findTop10ByOrderByPerformedAtDesc());
        model.addAttribute("engine", null);
        return "index";
    }
}
