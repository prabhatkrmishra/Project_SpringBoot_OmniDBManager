package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.repository.AuditLogRepository;
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

    public DashboardController(ProvisioningService provisioningService, AuditLogRepository auditLogRepository) {
        this.provisioningService = provisioningService;
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * Renders the dashboard: database list and recent activity.
     */
    @GetMapping("/")
    public String dashboard(Model model) {
        var mongoDbs = provisioningService.listDatabases(com.pkmprojects.mongodbserver.model.DatabaseEngineType.MONGO);
        model.addAttribute("databases", mongoDbs);
        model.addAttribute("mongoDatabases", mongoDbs);
        try {
            var pgDbs = provisioningService.listDatabases(com.pkmprojects.mongodbserver.model.DatabaseEngineType.POSTGRES);
            model.addAttribute("postgresDatabases", pgDbs);
            model.addAttribute("postgresCount", pgDbs.size());
        } catch (Exception e) {
            model.addAttribute("postgresDatabases", java.util.List.of());
            model.addAttribute("postgresCount", 0);
        }
        model.addAttribute("mongoCount", mongoDbs.size());
        model.addAttribute("recentActivity", auditLogRepository.findTop10ByOrderByPerformedAtDesc());
        model.addAttribute("engine", null);
        return "index";
    }
}
