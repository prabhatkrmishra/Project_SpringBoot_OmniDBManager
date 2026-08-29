package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.dto.CreateDatabaseForm;
import com.pkmprojects.mongodbserver.dto.DatabaseInfo;
import com.pkmprojects.mongodbserver.dto.ResetPasswordForm;
import com.pkmprojects.mongodbserver.error.NameNotAllowedException;
import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import com.pkmprojects.mongodbserver.service.PostgresBackupService;
import com.pkmprojects.mongodbserver.service.PostgresExplorationService;
import com.pkmprojects.mongodbserver.service.PostgresStatisticsService;
import com.pkmprojects.mongodbserver.service.ProvisioningService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Controller
@ConditionalOnProperty(name = "app.postgres.enabled", havingValue = "true")
@RequestMapping("/postgres")
public class PostgresController {

    private static final DateTimeFormatter FILENAME_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final ProvisioningService provisioningService;
    private final Optional<PostgresExplorationService> explorationService;
    private final Optional<PostgresStatisticsService> statisticsService;
    private final Optional<PostgresBackupService> backupService;
    private final Clock clock;

    public PostgresController(ProvisioningService provisioningService,
                              @Autowired(required = false) PostgresExplorationService explorationService,
                              @Autowired(required = false) PostgresStatisticsService statisticsService,
                              @Autowired(required = false) PostgresBackupService backupService,
                              Clock clock) {
        this.provisioningService = provisioningService;
        this.explorationService = Optional.ofNullable(explorationService);
        this.statisticsService = Optional.ofNullable(statisticsService);
        this.backupService = Optional.ofNullable(backupService);
        this.clock = clock;
    }

    @GetMapping
    public String home(Model model) {
        try {
            model.addAttribute("databases", provisioningService.listDatabases(DatabaseEngineType.POSTGRES));
        } catch (Exception e) {
            model.addAttribute("databases", java.util.List.of());
            model.addAttribute("postgresError", e.getMessage());
        }
        model.addAttribute("engine", DatabaseEngineType.POSTGRES);
        return "engine-home";
    }

    @PostMapping("/databases")
    @PreAuthorize("hasRole('ADMIN')")
    public String provision(@Valid @ModelAttribute("form") CreateDatabaseForm form,
                            BindingResult bindingResult,
                            @RequestParam(value = "enableVector", required = false, defaultValue = "false") boolean enableVector,
                            Model model, RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("engine", DatabaseEngineType.POSTGRES);
            model.addAttribute("vectorAvailable", provisioningService.isVectorAvailable());
            return "provision-postgres";
        }
        CreateDatabaseForm withEngine = new CreateDatabaseForm(form.dbName(), DatabaseEngineType.POSTGRES, form.userName(), form.password());
        DatabaseInfo created = provisioningService.provision(withEngine);
        if (enableVector) {
            try {
                provisioningService.enableVector(DatabaseEngineType.POSTGRES, created.dbName());
                redirectAttributes.addFlashAttribute("flashSuccess", "Database '" + created.dbName() + "' provisioned with pgvector");
            } catch (Exception e) {
                redirectAttributes.addFlashAttribute("flashSuccess", "Database '" + created.dbName() + "' provisioned (pgvector failed: " + e.getMessage() + ")");
            }
        } else {
            redirectAttributes.addFlashAttribute("flashSuccess", "Database '" + created.dbName() + "' provisioned");
        }
        redirectAttributes.addFlashAttribute("newCredentials", created);
        return "redirect:/postgres/databases/" + created.dbName();
    }

    @PostMapping("/databases/{dbName}/vector")
    @PreAuthorize("hasRole('ADMIN')")
    public String enableVector(@PathVariable String dbName, RedirectAttributes redirectAttributes) {
        try {
            provisioningService.enableVector(DatabaseEngineType.POSTGRES, dbName);
            redirectAttributes.addFlashAttribute("flashSuccess", "pgvector enabled on '" + dbName + "'");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/postgres/databases/" + dbName;
    }

    @GetMapping("/databases/{dbName}")
    public String detail(@PathVariable String dbName, Model model) {
        model.addAttribute("database", provisioningService.getDatabase(DatabaseEngineType.POSTGRES, dbName));
        explorationService.ifPresent(svc -> {
            try {
                model.addAttribute("tables", svc.listTables(dbName));
            } catch (Exception e) {
                model.addAttribute("tables", java.util.List.of());
            }
        });
        if (!model.containsAttribute("tables") && explorationService.isEmpty()) {
            model.addAttribute("tables", java.util.List.of());
        }
        model.addAttribute("engine", DatabaseEngineType.POSTGRES);
        model.addAttribute("vectorAvailable", provisioningService.isVectorAvailable());
        model.addAttribute("vectorEnabled", provisioningService.isVectorEnabled(DatabaseEngineType.POSTGRES, dbName));
        if (!model.containsAttribute("resetForm")) model.addAttribute("resetForm", new ResetPasswordForm(""));
        return "database";
    }

    @PostMapping("/databases/{dbName}/tables")
    @PreAuthorize("hasRole('ADMIN')")
    public String createTable(@PathVariable String dbName,
                              @RequestParam("tableName") String tableName,
                              @RequestParam(value = "columns", required = false) String columns,
                              RedirectAttributes redirectAttributes) {
        var svc = explorationService.orElseThrow(() -> new com.pkmprojects.mongodbserver.error.ProvisioningException("Postgres is not enabled"));
        try {
            java.util.List<String> cols = columns == null || columns.isBlank() ? java.util.List.of()
                    : java.util.Arrays.stream(columns.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
            svc.createTable(dbName, tableName.trim(), cols);
            redirectAttributes.addFlashAttribute("flashSuccess", "Table '" + tableName.trim() + "' created");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/postgres/databases/" + dbName;
    }

    @PostMapping("/databases/{dbName}/tables/{tableName}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public String dropTable(@PathVariable String dbName, @PathVariable String tableName, RedirectAttributes redirectAttributes) {
        var svc = explorationService.orElseThrow(() -> new com.pkmprojects.mongodbserver.error.ProvisioningException("Postgres is not enabled"));
        try {
            svc.dropTable(dbName, tableName);
            redirectAttributes.addFlashAttribute("flashSuccess", "Table '" + tableName + "' dropped");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/postgres/databases/" + dbName;
    }

    @PostMapping("/databases/{dbName}/tables/{tableName}/truncate")
    @PreAuthorize("hasRole('ADMIN')")
    public String truncateTable(@PathVariable String dbName, @PathVariable String tableName, RedirectAttributes redirectAttributes) {
        var svc = explorationService.orElseThrow(() -> new com.pkmprojects.mongodbserver.error.ProvisioningException("Postgres is not enabled"));
        try {
            svc.truncateTable(dbName, tableName);
            redirectAttributes.addFlashAttribute("flashSuccess", "Table '" + tableName + "' truncated");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/postgres/databases/" + dbName;
    }

    @PostMapping("/databases/{dbName}/tables/{tableName}/rows")
    @PreAuthorize("hasRole('ADMIN')")
    public String insertRow(@PathVariable String dbName, @PathVariable String tableName,
                            @RequestParam java.util.Map<String, String> allParams,
                            RedirectAttributes redirectAttributes) {
        var svc = explorationService.orElseThrow(() -> new com.pkmprojects.mongodbserver.error.ProvisioningException("Postgres is not enabled"));
        try {
            String newCol = allParams.get("__new_col");
            String newVal = allParams.get("__new_val");
            java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
            for (var e : allParams.entrySet()) {
                String k = e.getKey();
                if (k.equals("_csrf") || k.equals("ctid") || k.equals("__ctid") || k.equals("__pg_ctid") || k.equals("__new_col") || k.equals("__new_val")) continue;
                String v = e.getValue();
                if (v != null && !v.isBlank()) values.put(k, v);
                else if (v != null) values.put(k, null);
            }
            if (newCol != null && !newCol.isBlank()) {
                String nc = newCol.trim();
                Object nv = (newVal != null && !newVal.isBlank()) ? newVal : null;
                values.put(nc, nv);
            }
            if (values.isEmpty()) throw new com.pkmprojects.mongodbserver.error.NameNotAllowedException("Enter at least one column value");
            boolean hasValue = values.values().stream().anyMatch(v -> v != null);
            if (!hasValue) throw new com.pkmprojects.mongodbserver.error.NameNotAllowedException("Enter at least one non-empty value");
            svc.insertRow(dbName, tableName, values);
            redirectAttributes.addFlashAttribute("flashSuccess", "Row inserted into '" + tableName + "'");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/postgres/databases/" + dbName + "/tables/" + tableName;
    }

    @PostMapping("/databases/{dbName}/tables/{tableName}/rows/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public String deleteRow(@PathVariable String dbName, @PathVariable String tableName,
                            @RequestParam("ctid") String ctid,
                            RedirectAttributes redirectAttributes) {
        var svc = explorationService.orElseThrow(() -> new com.pkmprojects.mongodbserver.error.ProvisioningException("Postgres is not enabled"));
        try {
            svc.deleteRow(dbName, tableName, ctid);
            redirectAttributes.addFlashAttribute("flashSuccess", "Row deleted from '" + tableName + "'");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/postgres/databases/" + dbName + "/tables/" + tableName;
    }

    @GetMapping("/databases/{dbName}/tables/{tableName}")
    public String tableRows(@PathVariable String dbName, @PathVariable String tableName,
                            @RequestParam(name = "page", defaultValue = "1") int page, Model model) {
        model.addAttribute("database", provisioningService.getDatabase(DatabaseEngineType.POSTGRES, dbName));
        model.addAttribute("engine", DatabaseEngineType.POSTGRES);
        var svc = explorationService.orElseThrow(() -> new com.pkmprojects.mongodbserver.error.ProvisioningException("Postgres is not enabled"));
        model.addAttribute("tablePage", svc.getRows(dbName, tableName, page));
        return "table-rows";
    }

    @GetMapping("/databases/{dbName}/tables/{tableName}/export")
    public ResponseEntity<StreamingResponseBody> exportTable(@PathVariable String dbName, @PathVariable String tableName) {
        var svc = explorationService.orElseThrow(() -> new com.pkmprojects.mongodbserver.error.ProvisioningException("Postgres is not enabled"));
        // Validate before streaming so missing table yields 404 page, not truncated download
        svc.ensureTableExists(dbName, tableName);
        String filename = dbName + "." + tableName + ".json";
        StreamingResponseBody body = out -> svc.writeAllRowsAsJson(dbName, tableName, out);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    @GetMapping("/databases/{dbName}/stats")
    public String stats(@PathVariable String dbName, Model model) {
        model.addAttribute("database", provisioningService.getDatabase(DatabaseEngineType.POSTGRES, dbName));
        model.addAttribute("engine", DatabaseEngineType.POSTGRES);
        var svc = statisticsService.orElseThrow(() -> new com.pkmprojects.mongodbserver.error.ProvisioningException("Postgres is not enabled"));
        model.addAttribute("pgStats", svc.getDatabaseStats(dbName));
        return "stats-postgres";
    }

    @GetMapping("/databases/{dbName}/reset")
    @PreAuthorize("hasRole('ADMIN')")
    public String resetForm(@PathVariable String dbName, Model model) {
        model.addAttribute("database", provisioningService.getDatabase(DatabaseEngineType.POSTGRES, dbName));
        model.addAttribute("engine", DatabaseEngineType.POSTGRES);
        if (!model.containsAttribute("resetForm")) model.addAttribute("resetForm", new ResetPasswordForm(""));
        return "reset-password";
    }

    @PostMapping("/databases/{dbName}/reset")
    @PreAuthorize("hasRole('ADMIN')")
    public String resetPassword(@PathVariable String dbName,
                                @Valid @ModelAttribute("resetForm") ResetPasswordForm form,
                                BindingResult bindingResult, Model model, RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("database", provisioningService.getDatabase(DatabaseEngineType.POSTGRES, dbName));
            model.addAttribute("engine", DatabaseEngineType.POSTGRES);
            return "reset-password";
        }
        DatabaseInfo updated = provisioningService.resetPassword(DatabaseEngineType.POSTGRES, dbName, form);
        redirectAttributes.addFlashAttribute("flashSuccess", "Password reset for database '" + dbName + "'");
        redirectAttributes.addFlashAttribute("newCredentials", updated);
        return "redirect:/postgres/databases/" + dbName;
    }

    @GetMapping("/databases/{dbName}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public String deleteConfirm(@PathVariable String dbName, Model model) {
        model.addAttribute("database", provisioningService.getDatabase(DatabaseEngineType.POSTGRES, dbName));
        model.addAttribute("engine", DatabaseEngineType.POSTGRES);
        return "delete-confirm";
    }

    @PostMapping("/databases/{dbName}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public String delete(@PathVariable String dbName, RedirectAttributes redirectAttributes) {
        provisioningService.delete(DatabaseEngineType.POSTGRES, dbName);
        redirectAttributes.addFlashAttribute("flashSuccess", "Database '" + dbName + "' deleted");
        return "redirect:/postgres";
    }

    @GetMapping("/databases/{dbName}/users")
    @PreAuthorize("hasRole('ADMIN')")
    public String users(@PathVariable String dbName, Model model) {
        model.addAttribute("database", provisioningService.getDatabase(DatabaseEngineType.POSTGRES, dbName));
        model.addAttribute("users", provisioningService.listUsers(DatabaseEngineType.POSTGRES, dbName));
        model.addAttribute("engine", DatabaseEngineType.POSTGRES);
        return "users";
    }

    @PostMapping("/databases/{dbName}/users/{userName}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public String revokeUser(@PathVariable String dbName, @PathVariable String userName, RedirectAttributes redirectAttributes) {
        provisioningService.revokeUser(DatabaseEngineType.POSTGRES, dbName, userName);
        redirectAttributes.addFlashAttribute("flashSuccess", "User '" + userName + "' revoked from database '" + dbName + "'");
        return "redirect:/postgres/databases/" + dbName + "/users";
    }

    @GetMapping("/databases/{dbName}/backup")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<StreamingResponseBody> downloadBackup(@PathVariable String dbName) {
        var svc = backupService.orElseThrow(() -> new com.pkmprojects.mongodbserver.error.ProvisioningException("Postgres is not enabled"));
        svc.requireDatabaseExists(dbName);
        String filename = "backup-" + dbName + "-" + FILENAME_TIMESTAMP.format(clock.instant()) + ".json.gz";
        StreamingResponseBody body = out -> svc.writeBackup(dbName, out);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(body);
    }

    @GetMapping("/databases/{dbName}/restore")
    @PreAuthorize("hasRole('ADMIN')")
    public String restoreForm(@PathVariable String dbName, Model model) {
        var svc = backupService.orElseThrow(() -> new com.pkmprojects.mongodbserver.error.ProvisioningException("Postgres is not enabled"));
        model.addAttribute("database", svc.describeDatabase(dbName));
        model.addAttribute("engine", DatabaseEngineType.POSTGRES);
        return "restore";
    }

    @PostMapping(value = "/databases/{dbName}/restore", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public String restore(@PathVariable String dbName,
                          @RequestParam(value = "file", required = false) MultipartFile file,
                          @RequestParam(value = "confirm", required = false, defaultValue = "false") boolean confirm,
                          RedirectAttributes redirectAttributes) {
        if (file == null || file.isEmpty()) {
            redirectAttributes.addFlashAttribute("flashError", "Choose a backup file to restore");
            return "redirect:/postgres/databases/" + dbName + "/restore";
        }
        if (!confirm) {
            redirectAttributes.addFlashAttribute("flashError", "Check the confirmation box to replace the database's current data");
            return "redirect:/postgres/databases/" + dbName + "/restore";
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            redirectAttributes.addFlashAttribute("flashError", "Could not read the uploaded backup file");
            return "redirect:/postgres/databases/" + dbName + "/restore";
        }
        try {
            var svc = backupService.orElseThrow(() -> new com.pkmprojects.mongodbserver.error.ProvisioningException("Postgres is not enabled"));
            var result = svc.restore(dbName, content, true);
            redirectAttributes.addFlashAttribute("flashSuccess",
                    "Restored " + result.documentsRestored() + " rows across " + result.collectionsRestored() + " tables into '" + dbName + "'");
            return "redirect:/postgres/databases/" + dbName;
        } catch (NameNotAllowedException | com.pkmprojects.mongodbserver.error.DatabaseNotFoundException e) {
            redirectAttributes.addFlashAttribute("flashError", e.getMessage());
            return "redirect:/postgres/databases/" + dbName + "/restore";
        } catch (com.pkmprojects.mongodbserver.error.ProvisioningException e) {
            redirectAttributes.addFlashAttribute("flashError", e.getMessage());
            return "redirect:/postgres/databases/" + dbName + "/restore";
        }
    }
}
