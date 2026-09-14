package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.service.ReconciliationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * S-12 P3-1: operator diagnostics. ADMIN-only JSON surface for the
 * read-only resource reconciliation (metadata vs live engine catalogs).
 * Structured output, no secrets: names, statuses, and non-sensitive
 * details only. Never mutates anything — see
 * {@link ReconciliationService}.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminDiagnosticsController {

    private final ReconciliationService reconciliationService;

    public AdminDiagnosticsController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @GetMapping("/reconcile")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> reconcile() {
        ReconciliationService.ReconciliationReport report = reconciliationService.reconcile();
        return ResponseEntity.ok(Map.of(
                "ok", report.ok(),
                "engines", report.engines().stream().map(e -> Map.of(
                        "engine", e.engine(),
                        "state", e.state(),
                        "note", e.note(),
                        "resources", e.resources().stream().map(r -> Map.of(
                                "name", r.name(),
                                "status", r.status(),
                                "detail", r.detail())).toList())).toList()));
    }
}
