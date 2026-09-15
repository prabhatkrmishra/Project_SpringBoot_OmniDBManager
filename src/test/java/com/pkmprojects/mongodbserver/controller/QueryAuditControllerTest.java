package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.audit.QueryAuditStore;
import com.pkmprojects.mongodbserver.audit.QueryAuditStore.QueryAuditFilter;
import com.pkmprojects.mongodbserver.audit.collector.AuditCollectorService;
import com.pkmprojects.mongodbserver.config.SecurityConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MVC slice tests for the operator query-activity view and its auth boundary.
 */
@WebMvcTest({QueryAuditController.class, QueryAuditApiController.class})
@Import({SecurityConfig.class, QueryAuditControllerTest.SecurityTestConfig.class})
class QueryAuditControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QueryAuditStore queryAuditStore;

    @MockitoBean
    private AuditCollectorService collector;

    @MockitoBean
    private com.pkmprojects.mongodbserver.audit.collector.AuditTailRunner tails;

    @TestConfiguration(proxyBeanMethods = false)
    static class SecurityTestConfig {
        @Bean
        com.pkmprojects.mongodbserver.config.AdminProperties adminProperties() {
            return new com.pkmprojects.mongodbserver.config.AdminProperties("admin", "admin", false);
        }
    }

    @Test
    void queryActivityPageRendersForAdmin() throws Exception {
        when(queryAuditStore.countFiltered(any(QueryAuditFilter.class))).thenReturn(0L);
        when(queryAuditStore.findFiltered(any(QueryAuditFilter.class), anyInt(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/query-activity").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("query-activity"))
                .andExpect(model().attributeExists("events", "page", "totalPages", "totalCount"));
    }

    @Test
    void queryActivityApiRequiresAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/query-activity"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/api/admin/query-activity").with(user("bob").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void queryActivityApiReturnsEnvelopeForAdmin() throws Exception {
        when(queryAuditStore.countFiltered(any(QueryAuditFilter.class))).thenReturn(0L);
        when(queryAuditStore.findFiltered(any(QueryAuditFilter.class), anyInt(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/query-activity").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void anonymousPageIsRedirectedToLogin() throws Exception {
        mockMvc.perform(get("/query-activity"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }
}
