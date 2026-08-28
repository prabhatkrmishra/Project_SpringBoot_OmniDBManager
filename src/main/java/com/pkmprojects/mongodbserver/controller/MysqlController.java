package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.dto.CreateDatabaseForm;
import com.pkmprojects.mongodbserver.dto.DatabaseInfo;
import com.pkmprojects.mongodbserver.dto.ResetPasswordForm;
import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import com.pkmprojects.mongodbserver.service.ProvisioningService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/mysql")
public class MysqlController {

    private final ProvisioningService provisioningService;

    public MysqlController(ProvisioningService provisioningService) {
        this.provisioningService = provisioningService;
    }

    @GetMapping
    public String home(Model model) {
        try {
            model.addAttribute("databases", provisioningService.listDatabases(DatabaseEngineType.MYSQL));
        } catch (Exception e) {
            model.addAttribute("databases", java.util.List.of());
            model.addAttribute("mysqlError", e.getMessage());
        }
        model.addAttribute("engine", DatabaseEngineType.MYSQL);
        return "engine-home";
    }

    @PostMapping("/databases")
    @PreAuthorize("hasRole('ADMIN')")
    public String provision(@Valid @ModelAttribute("form") CreateDatabaseForm form,
                            BindingResult bindingResult, Model model, RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("engine", DatabaseEngineType.MYSQL);
            return "provision-mysql";
        }
        CreateDatabaseForm withEngine = new CreateDatabaseForm(form.dbName(), DatabaseEngineType.MYSQL, form.userName(), form.password());
        DatabaseInfo created = provisioningService.provision(withEngine);
        redirectAttributes.addFlashAttribute("flashSuccess", "Database '" + created.dbName() + "' provisioned");
        redirectAttributes.addFlashAttribute("newCredentials", created);
        return "redirect:/mysql/databases/" + created.dbName();
    }

    @GetMapping("/databases/{dbName}")
    public String detail(@PathVariable String dbName, Model model) {
        model.addAttribute("database", provisioningService.getDatabase(DatabaseEngineType.MYSQL, dbName));
        model.addAttribute("tables", java.util.List.of());
        model.addAttribute("engine", DatabaseEngineType.MYSQL);
        if (!model.containsAttribute("resetForm")) model.addAttribute("resetForm", new ResetPasswordForm(""));
        return "database";
    }

    @GetMapping("/databases/{dbName}/reset")
    @PreAuthorize("hasRole('ADMIN')")
    public String resetForm(@PathVariable String dbName, Model model) {
        model.addAttribute("database", provisioningService.getDatabase(DatabaseEngineType.MYSQL, dbName));
        model.addAttribute("engine", DatabaseEngineType.MYSQL);
        if (!model.containsAttribute("resetForm")) model.addAttribute("resetForm", new ResetPasswordForm(""));
        return "reset-password";
    }

    @PostMapping("/databases/{dbName}/reset")
    @PreAuthorize("hasRole('ADMIN')")
    public String resetPassword(@PathVariable String dbName,
                                @Valid @ModelAttribute("resetForm") ResetPasswordForm form,
                                BindingResult bindingResult, Model model, RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("database", provisioningService.getDatabase(DatabaseEngineType.MYSQL, dbName));
            model.addAttribute("engine", DatabaseEngineType.MYSQL);
            return "reset-password";
        }
        DatabaseInfo updated = provisioningService.resetPassword(DatabaseEngineType.MYSQL, dbName, form);
        redirectAttributes.addFlashAttribute("flashSuccess", "Password reset for database '" + dbName + "'");
        redirectAttributes.addFlashAttribute("newCredentials", updated);
        return "redirect:/mysql/databases/" + dbName;
    }

    @GetMapping("/databases/{dbName}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public String deleteConfirm(@PathVariable String dbName, Model model) {
        model.addAttribute("database", provisioningService.getDatabase(DatabaseEngineType.MYSQL, dbName));
        model.addAttribute("engine", DatabaseEngineType.MYSQL);
        return "delete-confirm";
    }

    @PostMapping("/databases/{dbName}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public String delete(@PathVariable String dbName, RedirectAttributes redirectAttributes) {
        provisioningService.delete(DatabaseEngineType.MYSQL, dbName);
        redirectAttributes.addFlashAttribute("flashSuccess", "Database '" + dbName + "' deleted");
        return "redirect:/mysql";
    }

    @GetMapping("/databases/{dbName}/users")
    @PreAuthorize("hasRole('ADMIN')")
    public String users(@PathVariable String dbName, Model model) {
        model.addAttribute("database", provisioningService.getDatabase(DatabaseEngineType.MYSQL, dbName));
        model.addAttribute("users", provisioningService.listUsers(DatabaseEngineType.MYSQL, dbName));
        model.addAttribute("engine", DatabaseEngineType.MYSQL);
        return "users";
    }

    @PostMapping("/databases/{dbName}/users/{userName}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public String revokeUser(@PathVariable String dbName, @PathVariable String userName, RedirectAttributes redirectAttributes) {
        provisioningService.revokeUser(DatabaseEngineType.MYSQL, dbName, userName);
        redirectAttributes.addFlashAttribute("flashSuccess", "User '" + userName + "' revoked from database '" + dbName + "'");
        return "redirect:/mysql/databases/" + dbName + "/users";
    }
}
