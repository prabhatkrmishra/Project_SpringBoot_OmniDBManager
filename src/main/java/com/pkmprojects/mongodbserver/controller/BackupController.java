package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.error.NameNotAllowedException;
import com.pkmprojects.mongodbserver.service.BackupService;
import com.pkmprojects.mongodbserver.util.BackupLimits;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Per-database backup download and restore upload (admin only). Restore replaces
 * the database's current content, so the form requires an explicit confirmation
 * checkbox, which the controller passes to {@link BackupService}.
 */
@Controller
@ConditionalOnProperty(name = "app.mongo.enabled", havingValue = "true")
public class BackupController {

    private static final DateTimeFormatter FILENAME_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final BackupService backupService;
    private final Clock clock;

    public BackupController(BackupService backupService, Clock clock) {
        this.backupService = backupService;
        this.clock = clock;
    }

    /**
     * Streams a gzip'd backup of the whole database as an attachment download.
     * The existence check runs before the response is returned, so a missing
     * database yields a normal 404 page instead of a broken download.
     */
    @GetMapping("/databases/{dbName}/backup")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<StreamingResponseBody> downloadBackup(@PathVariable String dbName) {
        backupService.requireDatabaseExists(dbName);
        String filename = "backup-" + dbName + "-" + FILENAME_TIMESTAMP.format(clock.instant()) + ".json.gz";
        StreamingResponseBody body = out -> backupService.writeBackup(dbName, out);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(body);
    }

    /**
     * Renders the restore form (admin only).
     */
    @GetMapping("/databases/{dbName}/restore")
    @PreAuthorize("hasRole('ADMIN')")
    public String restoreForm(@PathVariable String dbName, Model model) {
        model.addAttribute("database", backupService.describeDatabase(dbName));
        return "restore";
    }

    /**
     * Restores a database from an uploaded backup file (admin only). Requires
     * the confirmation checkbox, otherwise redirects back with an error.
     */
    @PostMapping(value = "/databases/{dbName}/restore", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public String restore(@PathVariable String dbName,
                          @RequestParam(value = "file", required = false) MultipartFile file,
                          @RequestParam(value = "confirm", required = false, defaultValue = "false") boolean confirm,
                          RedirectAttributes redirectAttributes) {
        if (file == null || file.isEmpty()) {
            redirectAttributes.addFlashAttribute("flashError", "Choose a backup file to restore");
            return redirectToRestore(dbName);
        }
        if (!confirm) {
            redirectAttributes.addFlashAttribute("flashError",
                    "Check the confirmation box to replace the database's current data");
            return redirectToRestore(dbName);
        }
        if (file.getSize() > BackupLimits.MAX_UPLOAD_BYTES) {
            redirectAttributes.addFlashAttribute("flashError",
                    "Backup file is too large (max " + BackupLimits.MAX_UPLOAD_BYTES + " bytes)");
            return redirectToRestore(dbName);
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            redirectAttributes.addFlashAttribute("flashError", "Could not read the uploaded backup file");
            return redirectToRestore(dbName);
        }
        try {
            BackupService.RestoreResult result = backupService.restore(dbName, content, true);
            redirectAttributes.addFlashAttribute("flashSuccess",
                    "Restored " + result.documentsRestored() + " documents across "
                            + result.collectionsRestored() + " collections into '" + dbName + "'");
            return "redirect:/databases/" + dbName;
        } catch (NameNotAllowedException e) {
            redirectAttributes.addFlashAttribute("flashError", e.getMessage());
            return redirectToRestore(dbName);
        }
    }

    private String redirectToRestore(String dbName) {
        return "redirect:/databases/" + dbName + "/restore";
    }
}
