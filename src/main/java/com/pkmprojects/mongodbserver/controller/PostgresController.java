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
                            BindingResult bindingResult, Model model, RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("engine", DatabaseEngineType.POSTGRES);
            return "provision-postgres";
        }
        CreateDatabaseForm withEngine = new CreateDatabaseForm(form.dbName(), DatabaseEngineType.POSTGRES, form.userName(), form.password());
        DatabaseInfo created = provisioningService.provision(withEngine);
        redirectAttributes.addFlashAttribute("flashSuccess", "Database '" + created.dbName() + "' provisioned");
        redirectAttributes.addFlashAttribute("newCredentials", created);
        return "redirect:/postgres/databases/" + created.dbName();
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
        if (!model.containsAttribute("resetForm")) model.addAttribute("resetForm", new ResetPasswordForm(""));
        return "database";
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
        } catch (NameNotAllowedException e) {
            redirectAttributes.addFlashAttribute("flashError", e.getMessage());
            return "redirect:/postgres/databases/" + dbName + "/restore";
        }
    }
}
