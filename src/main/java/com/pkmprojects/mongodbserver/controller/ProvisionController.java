package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.dto.CreateDatabaseForm;
import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import com.pkmprojects.mongodbserver.service.ProvisioningService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class ProvisionController {

    private final ProvisioningService provisioningService;
    private final boolean mongoEnabled;
    private final boolean postgresEnabled;
    private final boolean mysqlEnabled;

    public ProvisionController(ProvisioningService provisioningService,
                               @Value("${app.mongo.enabled:false}") boolean mongoEnabled,
                               @Value("${app.postgres.enabled:false}") boolean postgresEnabled,
                               @Value("${app.mysql.enabled:false}") boolean mysqlEnabled) {
        this.provisioningService = provisioningService;
        this.mongoEnabled = mongoEnabled;
        this.postgresEnabled = postgresEnabled;
        this.mysqlEnabled = mysqlEnabled;
    }

    @GetMapping("/provision")
    public String chooser(Model model) {
        int mongoCount;
        try {
            mongoCount = provisioningService.listDatabases(DatabaseEngineType.MONGO).size();
        } catch (Exception e) {
            mongoCount = 0;
        }
        model.addAttribute("mongoCount", mongoCount);
        try {
            model.addAttribute("postgresCount", provisioningService.listDatabases(DatabaseEngineType.POSTGRES).size());
        } catch (Exception e) {
            model.addAttribute("postgresCount", 0);
        }
        try {
            model.addAttribute("mysqlCount", provisioningService.listDatabases(DatabaseEngineType.MYSQL).size());
        } catch (Exception e) {
            model.addAttribute("mysqlCount", 0);
        }
        model.addAttribute("engine", null);
        return "provision";
    }

    @GetMapping("/provision/mongo")
    @PreAuthorize("hasRole('ADMIN')")
    public String mongoForm(Model model, RedirectAttributes redirectAttributes) {
        if (!mongoEnabled) {
            redirectAttributes.addFlashAttribute("flashError", "MongoDB is not enabled");
            return "redirect:/provision";
        }
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new CreateDatabaseForm("", DatabaseEngineType.MONGO, "", ""));
        }
        model.addAttribute("engine", DatabaseEngineType.MONGO);
        return "provision-mongo";
    }

    @GetMapping("/provision/postgres")
    @PreAuthorize("hasRole('ADMIN')")
    public String postgresForm(Model model, RedirectAttributes redirectAttributes) {
        if (!postgresEnabled) {
            redirectAttributes.addFlashAttribute("flashError", "PostgreSQL is not enabled");
            return "redirect:/provision";
        }
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new CreateDatabaseForm("", DatabaseEngineType.POSTGRES, "", ""));
        }
        model.addAttribute("engine", DatabaseEngineType.POSTGRES);
        model.addAttribute("vectorAvailable", provisioningService.isVectorAvailable());
        return "provision-postgres";
    }

    @GetMapping("/provision/mysql")
    @PreAuthorize("hasRole('ADMIN')")
    public String mysqlForm(Model model, RedirectAttributes redirectAttributes) {
        if (!mysqlEnabled) {
            redirectAttributes.addFlashAttribute("flashError", "MySQL is not enabled");
            return "redirect:/provision";
        }
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new CreateDatabaseForm("", DatabaseEngineType.MYSQL, "", ""));
        }
        model.addAttribute("engine", DatabaseEngineType.MYSQL);
        return "provision-mysql";
    }
}
