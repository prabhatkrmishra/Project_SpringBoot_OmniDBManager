package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.dto.CreateDatabaseForm;
import com.pkmprojects.mongodbserver.dto.DatabaseInfo;
import com.pkmprojects.mongodbserver.dto.ResetPasswordForm;
import com.pkmprojects.mongodbserver.service.ExplorationService;
import com.pkmprojects.mongodbserver.service.ProvisioningService;
import com.pkmprojects.mongodbserver.service.StatisticsService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Database detail, password reset, and deletion (admin-only writes).
 */
@Controller
public class DatabaseController {

    private final ProvisioningService provisioningService;
    private final ExplorationService explorationService;
    private final StatisticsService statisticsService;

    public DatabaseController(ProvisioningService provisioningService,
                              @Autowired(required = false) ExplorationService explorationService,
                              StatisticsService statisticsService) {
        this.provisioningService = provisioningService;
        this.explorationService = explorationService;
        this.statisticsService = statisticsService;
    }

    /**
     * Renders the provisioning form for a new database (admin only).
     * Legacy route — delegates to Mongo provision form.
     */
    @GetMapping("/databases/new")
    @PreAuthorize("hasRole('ADMIN')")
    public String newDatabaseForm(Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new CreateDatabaseForm("", com.pkmprojects.mongodbserver.model.DatabaseEngineType.MONGO, "", ""));
        }
        model.addAttribute("engine", com.pkmprojects.mongodbserver.model.DatabaseEngineType.MONGO);
        return "provision-mongo";
    }

    /**
     * Provisions a new database. On success redirects to the new database's
     * detail page with the show-once credentials in a flash attribute.
     */
    @PostMapping("/databases")
    @PreAuthorize("hasRole('ADMIN')")
    public String provision(@Valid @ModelAttribute("form") CreateDatabaseForm form,
                            BindingResult bindingResult, Model model, RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("engine", com.pkmprojects.mongodbserver.model.DatabaseEngineType.MONGO);
            return "provision-mongo";
        }
        DatabaseInfo created = provisioningService.provision(form);
        redirectAttributes.addFlashAttribute("flashSuccess", "Database '" + created.dbName() + "' provisioned");
        redirectAttributes.addFlashAttribute("newCredentials", created);
        return "redirect:/databases/" + created.dbName();
    }

    /**
     * Renders the database detail page with its collections.
     */
    @GetMapping("/databases/{dbName}")
    public String detail(@PathVariable String dbName, Model model) {
        model.addAttribute("database", provisioningService.getDatabase(dbName));
        if (explorationService != null) {
            model.addAttribute("collections", explorationService.listCollections(dbName));
        } else {
            model.addAttribute("collections", java.util.List.of());
        }
        if (!model.containsAttribute("resetForm")) {
            model.addAttribute("resetForm", new ResetPasswordForm(""));
        }
        return "database";
    }

    /**
     * Renders the password-reset confirmation page (admin only).
     */
    @GetMapping("/databases/{dbName}/reset")
    @PreAuthorize("hasRole('ADMIN')")
    public String resetForm(@PathVariable String dbName, Model model) {
        model.addAttribute("database", provisioningService.getDatabase(dbName));
        if (!model.containsAttribute("resetForm")) {
            model.addAttribute("resetForm", new ResetPasswordForm(""));
        }
        return "reset-password";
    }

    /**
     * Rotates the provisioned user's password (admin only). On success redirects
     * to the detail page with the show-once connection string.
     */
    @PostMapping("/databases/{dbName}/reset")
    @PreAuthorize("hasRole('ADMIN')")
    public String resetPassword(@PathVariable String dbName,
                                @Valid @ModelAttribute("resetForm") ResetPasswordForm form,
                                BindingResult bindingResult, Model model, RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("database", provisioningService.getDatabase(dbName));
            return "reset-password";
        }
        DatabaseInfo updated = provisioningService.resetPassword(dbName, form);
        redirectAttributes.addFlashAttribute("flashSuccess", "Password reset for database '" + dbName + "'");
        redirectAttributes.addFlashAttribute("newCredentials", updated);
        return "redirect:/databases/" + dbName;
    }

    /**
     * Renders the delete-confirmation page (admin only).
     */
    @GetMapping("/databases/{dbName}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public String deleteConfirm(@PathVariable String dbName, Model model) {
        model.addAttribute("database", provisioningService.getDatabase(dbName));
        return "delete-confirm";
    }

    /**
     * Deletes the database, its user, and its metadata (admin only).
     */
    @PostMapping("/databases/{dbName}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public String delete(@PathVariable String dbName, RedirectAttributes redirectAttributes) {
        provisioningService.delete(dbName);
        redirectAttributes.addFlashAttribute("flashSuccess", "Database '" + dbName + "' deleted");
        return "redirect:/";
    }

    /**
     * Renders the statistics dashboard for a database (read-only).
     */
    @GetMapping("/databases/{dbName}/stats")
    public String stats(@PathVariable String dbName, Model model) {
        model.addAttribute("database", provisioningService.getDatabase(dbName));
        model.addAttribute("stats", statisticsService.getDatabaseStats(dbName));
        return "stats";
    }

    /**
     * Renders the user management page for a database (admin only).
     */
    @GetMapping("/databases/{dbName}/users")
    @PreAuthorize("hasRole('ADMIN')")
    public String users(@PathVariable String dbName, Model model) {
        model.addAttribute("database", provisioningService.getDatabase(dbName));
        model.addAttribute("users", provisioningService.listUsers(dbName));
        return "users";
    }

    /**
     * Revokes a user's access to a database (admin only). Refuses to drop the
     * last remaining user.
     */
    @PostMapping("/databases/{dbName}/users/{userName}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public String revokeUser(@PathVariable String dbName, @PathVariable String userName,
                             RedirectAttributes redirectAttributes) {
        provisioningService.revokeUser(dbName, userName);
        redirectAttributes.addFlashAttribute("flashSuccess", "User '" + userName + "' revoked from database '" + dbName + "'");
        return "redirect:/databases/" + dbName + "/users";
    }
}
