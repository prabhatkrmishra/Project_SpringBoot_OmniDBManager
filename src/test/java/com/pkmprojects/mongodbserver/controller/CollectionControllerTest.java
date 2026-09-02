package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.config.AdminProperties;
import com.pkmprojects.mongodbserver.config.SecurityConfig;
import com.pkmprojects.mongodbserver.dto.DocumentPage;
import com.pkmprojects.mongodbserver.service.ExplorationService;
import com.pkmprojects.mongodbserver.service.ProvisioningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

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
 * MVC slice tests for the collection explorer, JSON export, and admin-only
 * collection create/drop.
 */
@WebMvcTest(CollectionController.class)
@Import({SecurityConfig.class, CollectionControllerTest.SecurityTestConfig.class})
@org.springframework.test.context.TestPropertySource(properties = "app.mongo.enabled=true")
class CollectionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ExplorationService explorationService;

    @MockitoBean
    private ProvisioningService provisioningService;

    private DocumentPage page() {
        return new DocumentPage("myapp", "items", 1, 50, 1, 1,
                List.of("{\"_id\": 1, \"name\": \"widget\"}"), false, false);
    }

    @Test
    void explorerRendersDocumentsAsEscapedText() throws Exception {
        when(explorationService.getDocuments("myapp", "items", 1)).thenReturn(page());

        mockMvc.perform(get("/databases/myapp/collections/items").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("collections"))
                .andExpect(model().attributeExists("page"))
                .andExpect(content().string(containsString("widget")));
    }

    @Test
    void exportDocumentsReturnsJsonAttachment() throws Exception {
        when(explorationService.exportDocumentsAsJson("myapp", "items", 1))
                .thenReturn("[{\"_id\": 1, \"name\": \"widget\"}]");

        mockMvc.perform(get("/databases/myapp/collections/items/export").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"myapp.items.page1.json\""))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("[{\"_id\": 1, \"name\": \"widget\"}]"));
    }

    @Test
    void exportDocumentsUsesRequestedPageInFilename() throws Exception {
        when(explorationService.exportDocumentsAsJson("myapp", "items", 3)).thenReturn("[]");

        mockMvc.perform(get("/databases/myapp/collections/items/export").param("page", "3")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"myapp.items.page3.json\""))
                .andExpect(content().json("[]"));
    }

    @Test
    void createCollectionAsAdminRedirectsToDetail() throws Exception {
        mockMvc.perform(post("/databases/myapp/collections")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("collectionName", "orders"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/databases/myapp"));

        verify(provisioningService).createCollection("myapp", "orders");
    }

    @Test
    void createCollectionWithInvalidNameRedirectsWithError() throws Exception {
        mockMvc.perform(post("/databases/myapp/collections")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("collectionName", "bad name!"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/databases/myapp"));

        verify(provisioningService, never()).createCollection(any(), any());
    }

    @Test
    void createCollectionAsUserIsForbidden() throws Exception {
        mockMvc.perform(post("/databases/myapp/collections")
                        .with(user("bob").roles("USER"))
                        .with(csrf())
                        .param("collectionName", "orders"))
                .andExpect(status().isForbidden());

        verify(provisioningService, never()).createCollection(any(), any());
    }

    @Test
    void dropCollectionAsAdminRedirectsToDetail() throws Exception {
        mockMvc.perform(post("/databases/myapp/collections/orders/delete")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/databases/myapp"));

        verify(provisioningService).dropCollection("myapp", "orders");
    }

    @Test
    void dropCollectionAsUserIsForbidden() throws Exception {
        mockMvc.perform(post("/databases/myapp/collections/orders/delete")
                        .with(user("bob").roles("USER"))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        verify(provisioningService, never()).dropCollection(any(), any());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SecurityTestConfig {
        @Bean
        AdminProperties adminProperties() {
            return new AdminProperties("admin", "admin", false);
        }
    }
}
