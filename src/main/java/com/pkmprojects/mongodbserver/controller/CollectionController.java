package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.dto.CreateCollectionForm;
import com.pkmprojects.mongodbserver.dto.DocumentPage;
import com.pkmprojects.mongodbserver.service.ExplorationService;
import com.pkmprojects.mongodbserver.service.ProvisioningService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Collection management (admin-only writes) and the paginated document explorer.
 * All writes go through {@link ProvisioningService} so existence and name rules
 * are enforced before touching MongoDB. Only loaded when
 * {@code app.mongo.enabled=true}.
 */
@Controller
@ConditionalOnProperty(name = "app.mongo.enabled", havingValue = "true")
public class CollectionController {

    private final ExplorationService explorationService;
    private final ProvisioningService provisioningService;

    public CollectionController(ExplorationService explorationService, ProvisioningService provisioningService) {
        this.explorationService = explorationService;
        this.provisioningService = provisioningService;
    }

    /**
     * Renders one page of documents of a collection (read-only explorer).
     */
    @GetMapping("/databases/{dbName}/collections/{collectionName}")
    public String documents(@PathVariable String dbName, @PathVariable String collectionName,
                            @RequestParam(name = "page", defaultValue = "1") int page, Model model) {
        DocumentPage documentPage = explorationService.getDocuments(dbName, collectionName, page);
        model.addAttribute("page", documentPage);
        return "collections";
    }

    /**
     * Downloads one page of a collection as a JSON attachment (read-only).
     */
    @GetMapping("/databases/{dbName}/collections/{collectionName}/export")
    public ResponseEntity<String> exportDocuments(@PathVariable String dbName, @PathVariable String collectionName,
                                                  @RequestParam(name = "page", defaultValue = "1") int page) {
        String json = explorationService.exportDocumentsAsJson(dbName, collectionName, page);
        // Names are validated URL-safe ([A-Za-z0-9_-]+), so they are safe in a filename.
        String filename = dbName + "." + collectionName + ".page" + Math.max(page, 1) + ".json";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }

    /**
     * Creates a collection inside an existing database (admin only).
     */
    @PostMapping("/databases/{dbName}/collections")
    @PreAuthorize("hasRole('ADMIN')")
    public String createCollection(@PathVariable String dbName,
                                   @Valid @ModelAttribute("collectionForm") CreateCollectionForm form,
                                   BindingResult bindingResult, RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("flashError", "Invalid collection name");
            return "redirect:/databases/" + dbName;
        }
        provisioningService.createCollection(dbName, form.collectionName());
        redirectAttributes.addFlashAttribute("flashSuccess", "Collection '" + form.collectionName() + "' created");
        return "redirect:/databases/" + dbName;
    }

    /**
     * Drops a collection (admin only).
     */
    @PostMapping("/databases/{dbName}/collections/{collectionName}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public String dropCollection(@PathVariable String dbName, @PathVariable String collectionName,
                                 RedirectAttributes redirectAttributes) {
        provisioningService.dropCollection(dbName, collectionName);
        redirectAttributes.addFlashAttribute("flashSuccess", "Collection '" + collectionName + "' dropped");
        return "redirect:/databases/" + dbName;
    }
}
