package com.pkmprojects.mongodbserver.store;

import com.pkmprojects.mongodbserver.model.WebhookConfig;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Optional;

/**
 * Abstraction over webhook config storage.
 * Mongo-backed when {@code app.mongo.enabled=true}, in-memory otherwise.
 */
public interface WebhookConfigStore {

    List<WebhookConfig> findAll(Sort sort);

    List<WebhookConfig> findAll();

    List<WebhookConfig> findByEnabledTrue();

    Optional<WebhookConfig> findById(String id);

    WebhookConfig save(WebhookConfig entity);

    void deleteById(String id);

    long count();
}
