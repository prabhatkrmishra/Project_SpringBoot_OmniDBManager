package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.error.NameNotAllowedException;
import com.pkmprojects.mongodbserver.service.ImportExportService;
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

/**
 * Bulk collection data transfer (admin-only imports). Exports stream the whole
 * collection as JSON or CSV; imports append documents from an uploaded file and
 * are never destructive.
 */
@Controller
@ConditionalOnProperty(name = "app.mongo.enabled", havingValue = "true")
public class ImportExportController {

    private final ImportExportService importExportService;

    public ImportExportController(ImportExportService importExportService) {
        this.importExportService = importExportService;
    }

    /**
     * Streams the whole collection as a JSON array attachment (read-only). The
     * existence check runs before the response is returned, so a missing
     * collection yields a 404 page instead of a truncated download.
     */
    @GetMapping("/databases/{dbName}/collections/{collectionName}/export/all")
    public ResponseEntity<StreamingResponseBody> exportAllJson(@PathVariable String dbName,
                                                               @PathVariable String collectionName) {
        importExportService.requireCollection(dbName, collectionName);
        // Names are validated URL-safe ([A-Za-z0-9_-]+), so they are safe in a filename.
        String filename = dbName + "." + collectionName + ".json";
        StreamingResponseBody body = out -> importExportService.writeAllDocumentsAsJson(dbName, collectionName, out);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /**
     * Streams the whole collection as a CSV attachment (read-only).
     */
    @GetMapping("/databases/{dbName}/collections/{collectionName}/export/all.csv")
    public ResponseEntity<StreamingResponseBody> exportAllCsv(@PathVariable String dbName,
                                                              @PathVariable String collectionName) {
        importExportService.requireCollection(dbName, collectionName);
        String filename = dbName + "." + collectionName + ".csv";
        StreamingResponseBody body = out -> importExportService.writeAllDocumentsAsCsv(dbName, collectionName, out);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(body);
    }

    /**
     * Renders the import form (admin only).
     */
    @GetMapping("/databases/{dbName}/collections/{collectionName}/import")
    @PreAuthorize("hasRole('ADMIN')")
    public String importForm(@PathVariable String dbName, @PathVariable String collectionName, Model model) {
        importExportService.requireCollection(dbName, collectionName);
        model.addAttribute("dbName", dbName);
        model.addAttribute("collectionName", collectionName);
        return "import";
    }

    /**
     * Appends documents from an uploaded file to the collection (admin only).
     */
    @PostMapping(value = "/databases/{dbName}/collections/{collectionName}/import",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public String importDocuments(@PathVariable String dbName, @PathVariable String collectionName,
                                  @RequestParam(value = "file", required = false) MultipartFile file,
                                  RedirectAttributes redirectAttributes) {
        if (file == null || file.isEmpty()) {
            redirectAttributes.addFlashAttribute("flashError", "Choose a file to import");
            return redirectToImport(dbName, collectionName);
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            redirectAttributes.addFlashAttribute("flashError", "Could not read the uploaded file");
            return redirectToImport(dbName, collectionName);
        }
        try {
            ImportExportService.ImportResult result = importExportService.importDocuments(dbName, collectionName, content);
            redirectAttributes.addFlashAttribute("flashSuccess",
                    "Imported " + result.documentsImported() + " document(s) into '" + collectionName + "'");
            return "redirect:/databases/" + dbName + "/collections/" + collectionName;
        } catch (NameNotAllowedException e) {
            redirectAttributes.addFlashAttribute("flashError", e.getMessage());
            return redirectToImport(dbName, collectionName);
        }
    }

    private String redirectToImport(String dbName, String collectionName) {
        return "redirect:/databases/" + dbName + "/collections/" + collectionName + "/import";
    }
}
