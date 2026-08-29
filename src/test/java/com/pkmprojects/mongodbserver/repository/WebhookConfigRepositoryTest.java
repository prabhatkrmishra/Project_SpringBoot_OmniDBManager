package com.pkmprojects.mongodbserver.repository;

import com.pkmprojects.mongodbserver.model.WebhookConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persistence checks for {@link WebhookConfigRepository} against a real MongoDB.
 * Verifies the entity round-trips through Spring Data (which maps it via field
 * access - the class has no setters) and lands in the {@code webhook_configs}
 * collection of the metadata database.
 *
 * <p>Skipped when Docker is unavailable (same convention as the other
 * container-based tests).</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@TestPropertySource(properties = {
        "app.mongo.enabled=true",
        "app.admin.username=admin",
        "app.admin.password=admin"
})
class WebhookConfigRepositoryTest {

    @Container
    static GenericContainer<?> mongo = new GenericContainer<>(DockerImageName.parse("mongo:8"))
            .withEnv("MONGO_INITDB_ROOT_USERNAME", "root")
            .withEnv("MONGO_INITDB_ROOT_PASSWORD", "root")
            .withExposedPorts(27017)
            // the entrypoint starts mongod twice (bootstrap for user creation, then the
            // real server), so wait for the second "waiting for connections"
            .waitingFor(Wait.forLogMessage("(?i).*waiting for connections.*", 2));

    @Autowired
    private WebhookConfigRepository webhookConfigRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () -> "mongodb://root:root@"
                + mongo.getHost() + ":" + mongo.getMappedPort(27017) + "/?authSource=admin");
    }

    @AfterEach
    void cleanup() {
        webhookConfigRepository.deleteAll();
    }

    private WebhookConfig webhook(String name, String secret, List<String> eventTypes, boolean enabled) {
        return new WebhookConfig(name, "https://example.com/hooks/" + name, secret, eventTypes, enabled,
                Instant.parse("2026-08-18T10:00:00Z"));
    }

    @Test
    void persistsAndReloadsWebhookConfig() {
        WebhookConfig saved = webhookConfigRepository.save(
                webhook("slack", "hunter2", List.of("PROVISION", "DELETE"), true));

        assertThat(saved.getId()).as("save assigns an id").isNotNull();
        assertThat(mongoTemplate.collectionExists("webhook_configs"))
                .as("config lands in the metadata database").isTrue();

        Optional<WebhookConfig> loaded = webhookConfigRepository.findById(saved.getId());
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getName()).isEqualTo("slack");
        assertThat(loaded.get().getUrl()).isEqualTo("https://example.com/hooks/slack");
        assertThat(loaded.get().getSecret()).isEqualTo("hunter2");
        assertThat(loaded.get().isEnabled()).isTrue();
        assertThat(loaded.get().getEventTypes()).containsExactly("PROVISION", "DELETE");
        assertThat(loaded.get().getCreatedAt()).isEqualTo(Instant.parse("2026-08-18T10:00:00Z"));
    }

    @Test
    void findByEnabledTrueReturnsOnlyEnabledWebhooks() {
        webhookConfigRepository.save(webhook("enabled-one", null, List.of(), true));
        webhookConfigRepository.save(webhook("disabled-one", null, List.of(), false));
        webhookConfigRepository.save(webhook("enabled-two", null, List.of(), true));

        List<WebhookConfig> enabled = webhookConfigRepository.findByEnabledTrue();

        assertThat(enabled).extracting(WebhookConfig::getName)
                .containsExactlyInAnyOrder("enabled-one", "enabled-two");
    }

    @Test
    void emptyEventTypesListRoundTripsAsEmpty() {
        WebhookConfig saved = webhookConfigRepository.save(webhook("all-events", null, List.of(), true));

        WebhookConfig loaded = webhookConfigRepository.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getEventTypes()).isEmpty();
    }

    @Test
    void togglePersistsEnabledFlag() {
        WebhookConfig saved = webhookConfigRepository.save(webhook("toggle-me", null, List.of(), true));

        saved.setEnabled(false);
        webhookConfigRepository.save(saved);

        WebhookConfig loaded = webhookConfigRepository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.isEnabled()).isFalse();
    }
}
