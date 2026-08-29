package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.config.AdminProperties;
import com.pkmprojects.mongodbserver.config.SecurityConfig;
import com.pkmprojects.mongodbserver.dto.ServerHealth;
import com.pkmprojects.mongodbserver.service.HealthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MVC slice tests for the health dashboard.
 */
@WebMvcTest(HealthController.class)
@Import({SecurityConfig.class, HealthControllerTest.SecurityTestConfig.class})
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HealthService healthService;

    @Test
    void healthPageRendersReachableServer() throws Exception {
        when(healthService.getHealth()).thenReturn(new ServerHealth(true, "7.0.39", 90061L, 3, 3072L, 5, true, false, null, false, false, null, false, true));

        mockMvc.perform(get("/health").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("health"))
                .andExpect(model().attributeExists("health"))
                .andExpect(content().string(containsString("Reachable")))
                .andExpect(content().string(containsString("7.0.39")));
    }

    @Test
    void healthPageRendersUnreachableServer() throws Exception {
        when(healthService.getHealth()).thenReturn(new ServerHealth(false, null, null, 0, null, null, false, false, null, false, false, null, false, true));

        mockMvc.perform(get("/health").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("health"))
                .andExpect(content().string(containsString("Unreachable")));
    }

    @Test
    void healthPageRendersForNonAdminReader() throws Exception {
        when(healthService.getHealth()).thenReturn(new ServerHealth(true, "7.0.39", 60L, 1, 1024L, 1));

        mockMvc.perform(get("/health").with(user("bob").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(view().name("health"));
    }

    @Test
    void anonymousUserIsRedirectedToLogin() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SecurityTestConfig {
        @Bean
        AdminProperties adminProperties() {
            return new AdminProperties("admin", "admin");
        }
    }
}