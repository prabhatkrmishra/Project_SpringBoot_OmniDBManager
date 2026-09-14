package com.pkmprojects.mongodbserver.config;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Orchestrator probes against the real actuator wiring.
 * Liveness/readiness are anonymous and minimal (UP/DOWN only); the full
 * health endpoint stays behind authentication; and a dead metadata store
 * degrades readiness WITHOUT taking liveness down (tenant/control-store
 * failures must not look like a dead process).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.admin.username=admin",
        "app.admin.password=admin",
        "app.mongo.enabled=true",
        // This fixture has no PostgreSQL/MySQL: disable their configs so the
        // readiness probe measures exactly the live mongo store, not absent
        // engines (absent beans are ignored by design).
        "app.postgres.enabled=false",
        "app.mysql.enabled=false"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProbeSecurityTest {

    @Container
    static GenericContainer<?> mongo = new GenericContainer<>(DockerImageName.parse("mongo:8"))
            .withEnv("MONGO_INITDB_ROOT_USERNAME", "root")
            .withEnv("MONGO_INITDB_ROOT_PASSWORD", "root")
            .withExposedPorts(27017)
            .waitingFor(Wait.forLogMessage("(?i).*waiting for connections.*", 2));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () -> "mongodb://root:root@"
                + mongo.getHost() + ":" + mongo.getMappedPort(27017) + "/?authSource=admin");
    }

    @Autowired private MockMvc mockMvc;

    @Test
    @Order(1)
    void livenessIsAnonymousAndMinimal() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("password"))));
    }

    @Test
    @Order(2)
    void readinessIsAnonymousWhenHealthy() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @Order(3)
    void fullHealthIsNotAnonymous() throws Exception {
        // Authenticated browser flow redirects anonymous users to /login;
        // the detail body must never be served without credentials.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @Order(4)
    void deadStoreDegradesReadinessButNotLiveness() throws Exception {
        mongo.stop();
        try {
            mockMvc.perform(get("/actuator/health/readiness"))
                    .andExpect(status().isServiceUnavailable());
            mockMvc.perform(get("/actuator/health/liveness"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
        } finally {
            mongo.start();
        }
    }
}
