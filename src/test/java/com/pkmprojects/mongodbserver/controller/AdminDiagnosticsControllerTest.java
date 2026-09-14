package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.config.SecurityConfig;
import com.pkmprojects.mongodbserver.service.ReconciliationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reconciliation is ADMIN-only structured JSON without secrets.
 */
@WebMvcTest(AdminDiagnosticsController.class)
@Import({SecurityConfig.class, AdminDiagnosticsControllerTest.SecurityTestConfig.class})
class AdminDiagnosticsControllerTest {

    @Autowired private MockMvc mockMvc;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private ReconciliationService reconciliationService;

    @TestConfiguration
    static class SecurityTestConfig {
        @Bean com.pkmprojects.mongodbserver.config.AdminProperties adminProperties() {
            return new com.pkmprojects.mongodbserver.config.AdminProperties("admin", "admin", false);
        }
    }

    private static ReconciliationService.ReconciliationReport report() {
        return new ReconciliationService.ReconciliationReport(true, List.of(
                new ReconciliationService.EngineReport("POSTGRES", "OK", List.of(
                        new ReconciliationService.ResourceEntry("a", "HEALTHY", "database+role present")), "")));
    }

    @Test
    void adminGetsStructuredReport() throws Exception {
        when(reconciliationService.reconcile()).thenReturn(report());
        mockMvc.perform(get("/api/admin/reconcile").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.engines[0].engine").value("POSTGRES"))
                .andExpect(jsonPath("$.engines[0].resources[0].status").value("HEALTHY"));
    }

    @Test
    void anonymousIsNotServed() throws Exception {
        // Form-login setup redirects anonymous browser requests to /login;
        // either way the report body must never be served.
        mockMvc.perform(get("/api/admin/reconcile"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void nonAdminIsForbidden() throws Exception {
        mockMvc.perform(get("/api/admin/reconcile").with(user("bob").roles("USER")))
                .andExpect(status().isForbidden());
    }
}
