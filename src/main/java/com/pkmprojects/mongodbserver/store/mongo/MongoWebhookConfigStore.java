package com.pkmprojects.mongodbserver.store.mongo;

import com.pkmprojects.mongodbserver.model.WebhookConfig;
import com.pkmprojects.mongodbserver.repository.WebhookConfigRepository;
import com.pkmprojects.mongodbserver.store.WebhookConfigStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "app.mongo.enabled", havingValue = "true")
public class MongoWebhookConfigStore implements WebhookConfigStore {

    private final WebhookConfigRepository delegate;

    public MongoWebhookConfigStore(WebhookConfigRepository delegate) {
        this.delegate = delegate;
    }

    @Override public List<WebhookConfig> findAll(Sort sort) { return delegate.findAll(sort); }
    @Override public List<WebhookConfig> findAll() { return delegate.findAll(); }
    @Override public List<WebhookConfig> findByEnabledTrue() { return delegate.findByEnabledTrue(); }
    @Override public Optional<WebhookConfig> findById(String id) { return delegate.findById(id); }
    @Override public WebhookConfig save(WebhookConfig e) { return delegate.save(e); }
    @Override public void deleteById(String id) { delegate.deleteById(id); }
    @Override public long count() { return delegate.count(); }
}
