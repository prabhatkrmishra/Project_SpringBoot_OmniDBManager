package com.pkmprojects.mongodbserver.store.inmemory;

import com.pkmprojects.mongodbserver.model.WebhookConfig;
import com.pkmprojects.mongodbserver.store.WebhookConfigStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name = "app.mongo.enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryWebhookConfigStore implements WebhookConfigStore {

    private final Map<String, WebhookConfig> byId = new ConcurrentHashMap<>();

    @Override
    public List<WebhookConfig> findAll(Sort sort) {
        // Only use is createdAt ASC; respect it
        Comparator<WebhookConfig> cmp = Comparator.comparing(WebhookConfig::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()));
        if (sort != null && sort.getOrderFor("createdAt") != null && sort.getOrderFor("createdAt").isDescending()) {
            cmp = cmp.reversed();
        }
        return byId.values().stream().sorted(cmp).toList();
    }

    @Override
    public List<WebhookConfig> findAll() {
        return List.copyOf(byId.values());
    }

    @Override
    public List<WebhookConfig> findByEnabledTrue() {
        return byId.values().stream().filter(WebhookConfig::isEnabled).toList();
    }

    @Override
    public Optional<WebhookConfig> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public WebhookConfig save(WebhookConfig entity) {
        // Assign id if missing (mirror Spring Data behavior)
        if (entity.getId() == null) {
            try {
                var f = WebhookConfig.class.getDeclaredField("id");
                f.setAccessible(true);
                if (f.get(entity) == null) {
                    f.set(entity, UUID.randomUUID().toString());
                }
            } catch (Exception ignored) {}
        }
        if (entity.getId() == null) {
            throw new IllegalArgumentException("Webhook config id must not be null");
        }
        byId.put(entity.getId(), entity);
        return entity;
    }

    @Override
    public void deleteById(String id) {
        byId.remove(id);
    }

    @Override
    public long count() {
        return byId.size();
    }
}
