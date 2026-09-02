package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.config.AdminProperties;
import com.pkmprojects.mongodbserver.config.SecurityConfig;
import com.pkmprojects.mongodbserver.dto.WebhookForm;
import com.pkmprojects.mongodbserver.error.WebhookNotFoundException;
import com.pkmprojects.mongodbserver.model.WebhookConfig;
import com.pkmprojects.mongodbserver.service.WebhookService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MVC slice tests for the webhook management page.
 */
@WebMvcTest(WebhookController.class)
@Import({SecurityConfig.class, WebhookControllerTest.SecurityTestConfig.class})
class WebhookControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-18T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WebhookService webhookService;

    private WebhookConfig webhook(String id, String name, boolean enabled) {
        WebhookConfig config = new WebhookConfig(name, "https://example.com/hooks", null, List.of(), enabled, NOW);
        return config;
    }

    @Test
    void webhooksPageRendersForAdmin() throws Exception {
        when(webhookService.listWebhooks()).thenReturn(List.of(webhook("w1", "Slack", true)));

        mockMvc.perform(get("/webhooks").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("webhooks"))
                .andExpect(model().attributeExists("webhooks", "eventTypes", "form"))
                .andExpect(content().string(containsString("Slack")));
    }

    @Test
    void webhooksPageRequiresAdminRole() throws Exception {
        mockMvc.perform(get("/webhooks").with(user("bob").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousUserIsRedirectedToLogin() throws Exception {
        mockMvc.perform(get("/webhooks"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void createWebhookAsAdminRedirectsToList() throws Exception {
        when(webhookService.createWebhook(any(WebhookForm.class)))
                .thenReturn(webhook("w1", "Slack", true));

        mockMvc.perform(post("/webhooks")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("name", "Slack")
                        .param("url", "https://example.com/hooks")
                        .param("eventTypes", "PROVISION"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/webhooks"));

        verify(webhookService).createWebhook(any(WebhookForm.class));
    }

    @Test
    void createWebhookWithInvalidInputRerendersForm() throws Exception {
        mockMvc.perform(post("/webhooks")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("name", "x".repeat(65))
                        .param("url", "x".repeat(501)))
                .andExpect(status().isOk())
                .andExpect(view().name("webhooks"))
                .andExpect(model().attributeHasFieldErrors("form", "name", "url"));

        verify(webhookService, never()).createWebhook(any());
    }

    @Test
    void createWebhookWithoutCsrfIsRejected() throws Exception {
        mockMvc.perform(post("/webhooks").with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());

        verify(webhookService, never()).createWebhook(any());
    }

    @Test
    void toggleWebhookRedirectsToList() throws Exception {
        mockMvc.perform(post("/webhooks/w1/toggle")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/webhooks"));

        verify(webhookService).toggleWebhook("w1");
    }

    @Test
    void deleteWebhookRedirectsToList() throws Exception {
        mockMvc.perform(post("/webhooks/w1/delete")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/webhooks"));

        verify(webhookService).deleteWebhook("w1");
    }

    @Test
    void toggleAsUserIsForbidden() throws Exception {
        mockMvc.perform(post("/webhooks/w1/toggle")
                        .with(user("bob").roles("USER"))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        verify(webhookService, never()).toggleWebhook(any());
    }

    @Test
    void missingWebhookReturns404() throws Exception {
        doThrow(new WebhookNotFoundException("Webhook not found"))
                .when(webhookService).toggleWebhook("missing");

        mockMvc.perform(post("/webhooks/missing/toggle")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SecurityTestConfig {
        @Bean
        AdminProperties adminProperties() {
            return new AdminProperties("admin", "admin", false);
        }
    }
}
