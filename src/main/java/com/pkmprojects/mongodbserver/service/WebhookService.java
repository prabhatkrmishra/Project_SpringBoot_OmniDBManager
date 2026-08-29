package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.dto.WebhookForm;
import com.pkmprojects.mongodbserver.error.NameNotAllowedException;
import com.pkmprojects.mongodbserver.error.WebhookNotFoundException;
import com.pkmprojects.mongodbserver.model.AuditEvent;
import com.pkmprojects.mongodbserver.model.AuditEventRecorded;
import com.pkmprojects.mongodbserver.model.WebhookConfig;
import com.pkmprojects.mongodbserver.store.AuditStore;
import com.pkmprojects.mongodbserver.store.WebhookConfigStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Manages webhook endpoint configuration: create, toggle, delete. Every change
 * is written to the audit trail. URLs are restricted to http(s) so the notifier
 * never dials arbitrary schemes.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private static final String[] BLOCKED_HOSTS = {
            "localhost", "127.0.0.1", "::1", "0.0.0.0", "0:0:0:0:0:0:0:0",
            "169.254.169.254", "metadata.google.internal", "metadata.internal"
    };

    private final WebhookConfigStore webhookConfigStore;
    private final AuditStore auditStore;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final Clock clock;

    public WebhookService(WebhookConfigStore webhookConfigStore,
                          AuditStore auditStore,
                          ApplicationEventPublisher applicationEventPublisher, Clock clock) {
        this.webhookConfigStore = webhookConfigStore;
        this.auditStore = auditStore;
        this.applicationEventPublisher = applicationEventPublisher;
        this.clock = clock;
    }

    /**
     * @return all webhook endpoints, oldest first
     */
    public List<WebhookConfig> listWebhooks() {
        return webhookConfigStore.findAll(Sort.by(Sort.Direction.ASC, "createdAt"));
    }

    /**
     * Creates a webhook endpoint and audits the change.
     *
     * @throws NameNotAllowedException when the name/url/secret/event types are invalid
     */
    public WebhookConfig createWebhook(WebhookForm form) {
        validate(form);
        List<String> eventTypes = form.eventTypes() == null ? List.of() : form.eventTypes();
        String secret = normalizeSecret(form.secret());
        WebhookConfig webhook = new WebhookConfig(
                form.name().trim(), form.url().trim(), secret, eventTypes, true, clock.instant());
        WebhookConfig saved = webhookConfigStore.save(webhook);
        audit(AuditEvent.WEBHOOK_CREATED, saved.getName(), clock.instant());
        log.info("Created webhook '{}'", saved.getName());
        return saved;
    }

    /**
     * Enables or disables a webhook endpoint and audits the change.
     *
     * @throws WebhookNotFoundException when the id does not exist
     */
    public void toggleWebhook(String id) {
        WebhookConfig webhook = requireWebhook(id);
        webhook.setEnabled(!webhook.isEnabled());
        webhookConfigStore.save(webhook);
        audit(AuditEvent.WEBHOOK_UPDATED, webhook.getName(), clock.instant());
        log.info("{} webhook '{}'", webhook.isEnabled() ? "Enabled" : "Disabled", webhook.getName());
    }

    /**
     * Deletes a webhook endpoint and audits the change.
     *
     * @throws WebhookNotFoundException when the id does not exist
     */
    public void deleteWebhook(String id) {
        WebhookConfig webhook = requireWebhook(id);
        webhookConfigStore.deleteById(id);
        audit(AuditEvent.WEBHOOK_DELETED, webhook.getName(), clock.instant());
        log.info("Deleted webhook '{}'", webhook.getName());
    }

    private WebhookConfig requireWebhook(String id) {
        return webhookConfigStore.findById(id)
                .orElseThrow(() -> new WebhookNotFoundException("Webhook not found"));
    }

    private static String normalizeSecret(String secret) {
        if (secret == null) {
            return null;
        }
        String trimmed = secret.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void validate(WebhookForm form) {
        if (form.name() == null || form.name().isBlank()) {
            throw new NameNotAllowedException("Webhook name is required");
        }
        validateUrl(form.url());
        if (form.secret() != null && form.secret().length() > 128) {
            throw new NameNotAllowedException("Webhook secret must be at most 128 characters");
        }
        if (form.eventTypes() != null) {
            for (String eventType : form.eventTypes()) {
                if (!AuditEvent.ALL_TYPES.contains(eventType)) {
                    throw new NameNotAllowedException("Unknown event type '" + eventType + "'");
                }
            }
        }
    }

    private void validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new NameNotAllowedException("Webhook URL is required");
        }
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null) {
                throw new NameNotAllowedException("Webhook URL must be a valid http(s) URL");
            }
            String host = uri.getHost();
            for (String blocked : BLOCKED_HOSTS) {
                if (blocked.equalsIgnoreCase(host)) {
                    throw new NameNotAllowedException("Webhook URL targets a blocked host: " + host);
                }
            }
            if (isPrivateIp(host)) {
                throw new NameNotAllowedException("Webhook URL resolves to a private/internal IP address");
            }
        } catch (IllegalArgumentException e) {
            throw new NameNotAllowedException("Webhook URL must be a valid http(s) URL");
        }
    }

    private static boolean isPrivateIp(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                if (addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()
                        || addr.isAnyLocalAddress()) {
                    return true;
                }
            }
        } catch (UnknownHostException e) {
            // Cannot resolve yet; allow validation to proceed, delivery will fail later
        }
        return false;
    }

    private void audit(String eventType, String webhookName, Instant performedAt) {
        AuditEvent event = new AuditEvent(eventType, null, webhookName, currentUsername(), performedAt);
        auditStore.save(event);
        // Publish like every other audit writer so webhooks subscribed to the
        // WEBHOOK_* event types actually receive them (WebhookNotifier listens
        // for AuditEventRecorded).
        applicationEventPublisher.publishEvent(new AuditEventRecorded(event));
    }

    private String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getName() != null ? authentication.getName() : "unknown";
    }
}