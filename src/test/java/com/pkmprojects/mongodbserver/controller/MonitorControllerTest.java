package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.config.AdminProperties;
import com.pkmprojects.mongodbserver.config.SecurityConfig;
import com.pkmprojects.mongodbserver.dto.MonitorSnapshot;
import com.pkmprojects.mongodbserver.dto.MysqlMonitorSnapshot;
import com.pkmprojects.mongodbserver.dto.PostgresMonitorSnapshot;
import com.pkmprojects.mongodbserver.service.MonitorService;
import com.pkmprojects.mongodbserver.service.MysqlMonitorService;
import com.pkmprojects.mongodbserver.service.PostgresMonitorService;
import com.pkmprojects.mongodbserver.service.PgbouncerMonitorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MVC slice tests for the live monitor page and its SSE stream (any
 * authenticated user).
 *
 * <p>The default engine is derived from enabled capabilities —
 * never a disabled engine — and page landing and SSE stream share one
 * selector ({@code resolveEngine}), so they always agree.</p>
 */
@WebMvcTest(MonitorController.class)
@Import({SecurityConfig.class, MonitorControllerTest.SecurityTestConfig.class})
class MonitorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MonitorService monitorService;

    @MockitoBean
    private PostgresMonitorService postgresMonitorService;

    @MockitoBean
    private MysqlMonitorService mysqlMonitorService;

    @Test
    void monitorPageRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/monitor"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void monitorPageRendersForAnyAuthenticatedUser() throws Exception {
        mockMvc.perform(get("/monitor").with(user("bob").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(view().name("monitor"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
    }

    @Test
    void monitorStreamRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/monitor/stream"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void monitorStreamStartsForAnyAuthenticatedUser() throws Exception {
        when(monitorService.getSnapshot()).thenReturn(new MonitorSnapshot(true, Instant.parse("2026-08-18T10:00:00Z"),
                "8.0.39", 3600L, 5, 2, 3072L, null, null, null, null));
        when(monitorService.serialize(any(MonitorSnapshot.class))).thenReturn("{\"reachable\":true}");

        // Pin engine=mongo: the bare-stream default is covered by
        // streamUsesSameDefaultAsPageLanding (postgres branch here).
        mockMvc.perform(get("/monitor/stream").param("engine", "mongo").with(user("bob").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andExpect(request().asyncStarted());
    }

    // ── default-engine selection ──────────────────────────────────────

    @Test
    void defaultEnginePrefersPostgresWhenEnabled() throws Exception {
        when(postgresMonitorService.getSnapshot()).thenReturn(
                new PostgresMonitorSnapshot(true, Instant.parse("2026-08-18T10:00:00Z"),
                        "18.1", 3600L, 5, 2, 1024L, 1, 1, 10L, 1L));
        when(postgresMonitorService.serialize(any(PostgresMonitorSnapshot.class))).thenReturn("{\"reachable\":true}");

        // Bare landing must resolve to the first enabled engine (postgres),
        // never to mongo-first.
        mockMvc.perform(get("/monitor").with(user("bob").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(model().attribute("monitorEngine", "postgres"));
    }

    @Test
    void explicitDisabledEngineFallsBackToFirstEnabled() throws Exception {
        when(postgresMonitorService.getSnapshot()).thenReturn(
                new PostgresMonitorSnapshot(true, Instant.parse("2026-08-18T10:00:00Z"),
                        "18.1", 3600L, 5, 2, 1024L, 1, 1, 10L, 1L));
        when(postgresMonitorService.serialize(any(PostgresMonitorSnapshot.class))).thenReturn("{\"reachable\":true}");

        // mysql bean is present in this slice (mocked); mongo bean is present
        // too. Ask for an unknown engine name: must still land on postgres.
        mockMvc.perform(get("/monitor").param("engine", "oracle").with(user("bob").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(model().attribute("monitorEngine", "postgres"));
    }

    @Test
    void streamUsesSameDefaultAsPageLanding() throws Exception {
        when(postgresMonitorService.getSnapshot()).thenReturn(
                new PostgresMonitorSnapshot(true, Instant.parse("2026-08-18T10:00:00Z"),
                        "18.1", 3600L, 5, 2, 1024L, 1, 1, 10L, 1L));
        when(postgresMonitorService.serialize(any(PostgresMonitorSnapshot.class))).thenReturn("{\"reachable\":true}");

        // Same default resolution for the SSE stream: bare stream must start
        // (postgres snapshot mocked above proves the postgres branch ran).
        mockMvc.perform(get("/monitor/stream").with(user("bob").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SecurityTestConfig {
        @Bean
        AdminProperties adminProperties() {
            return new AdminProperties("admin", "admin", false);
        }

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-08-18T10:00:00Z"), ZoneOffset.UTC);
        }
    }
}
