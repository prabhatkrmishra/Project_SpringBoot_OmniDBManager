package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.dto.WebhookForm;
import com.pkmprojects.mongodbserver.error.NameNotAllowedException;
import com.pkmprojects.mongodbserver.error.WebhookNotFoundException;
import com.pkmprojects.mongodbserver.model.AuditEvent;
import com.pkmprojects.mongodbserver.model.AuditEventRecorded;
import com.pkmprojects.mongodbserver.model.WebhookConfig;
import com.pkmprojects.mongodbserver.repository.AuditLogRepository;
import com.pkmprojects.mongodbserver.repository.WebhookConfigRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for webhook configuration management (mock repositories).
 */
@ExtendWith(MockitoExtension.class)
class WebhookServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-18T10:00:00Z");

    @Mock
    private WebhookConfigRepository webhookConfigRepository;
    @Mock
    private AuditLogRepository auditLogRepository;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    private WebhookService service;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        service = new WebhookService(webhookConfigRepository, auditLogRepository, applicationEventPublisher,
                Clock.fixed(NOW, ZoneOffset.UTC));
        // Only the create tests read the returned config; the toggle/delete tests
        // also call save() but ignore the result.
        lenient().when(webhookConfigRepository.save(any(WebhookConfig.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private WebhookConfig webhook(String id, String name, boolean enabled) {
        return new WebhookConfig(name, "https://example.com/hooks", null, List.of(), enabled, NOW);
    }

    @Test
    void createWebhookSavesEnabledConfigAndAudits() {
        WebhookConfig saved = service.createWebhook(new WebhookForm("Slack", "https://example.com/hooks", "secret", List.of()));

        ArgumentCaptor<WebhookConfig> captor = ArgumentCaptor.forClass(WebhookConfig.class);
        verify(webhookConfigRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("Slack");
        assertThat(captor.getValue().getUrl()).isEqualTo("https://example.com/hooks");
        assertThat(captor.getValue().getSecret()).isEqualTo("secret");
        assertThat(captor.getValue().isEnabled()).isTrue();
        assertThat(captor.getValue().getEventTypes()).isEmpty();
        assertThat(captor.getValue().getCreatedAt()).isEqualTo(NOW);
        assertThat(saved.isEnabled()).isTrue();

        ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getEventType()).isEqualTo(AuditEvent.WEBHOOK_CREATED);
        assertThat(auditCaptor.getValue().getUserName()).isEqualTo("Slack");
        assertThat(auditCaptor.getValue().getPerformedBy()).isEqualTo("admin");
        assertThat(auditCaptor.getValue().getPerformedAt()).isEqualTo(NOW);

        // The audit event must be published so subscribed webhooks receive it.
        ArgumentCaptor<AuditEventRecorded> publishedCaptor = ArgumentCaptor.forClass(AuditEventRecorded.class);
        verify(applicationEventPublisher).publishEvent(publishedCaptor.capture());
        assertThat(publishedCaptor.getValue().event()).isSameAs(auditCaptor.getValue());
    }

    @Test
    void createWebhookKeepsSelectedEventTypes() {
        service.createWebhook(new WebhookForm("Slack", "https://example.com/hooks", null,
                List.of(AuditEvent.PROVISION, AuditEvent.DELETE)));

        ArgumentCaptor<WebhookConfig> captor = ArgumentCaptor.forClass(WebhookConfig.class);
        verify(webhookConfigRepository).save(captor.capture());
        assertThat(captor.getValue().getEventTypes()).containsExactly(AuditEvent.PROVISION, AuditEvent.DELETE);
    }

    @Test
    void createWebhookTrimsSecretAndTreatsBlankAsNone() {
        service.createWebhook(new WebhookForm("Slack", "https://example.com/hooks", "  hunter2  ", List.of()));
        service.createWebhook(new WebhookForm("Ding", "https://example.com/hooks", "   ", List.of()));

        ArgumentCaptor<WebhookConfig> captor = ArgumentCaptor.forClass(WebhookConfig.class);
        verify(webhookConfigRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getSecret()).isEqualTo("hunter2");
        assertThat(captor.getAllValues().get(1).getSecret()).isNull();
    }

    @Test
    void createWebhookRejectsBlankName() {
        assertThatThrownBy(() -> service.createWebhook(new WebhookForm(" ", "https://example.com/hooks", null, List.of())))
                .isInstanceOf(NameNotAllowedException.class);
    }

    @Test
    void createWebhookRejectsNonHttpUrl() {
        assertThatThrownBy(() -> service.createWebhook(new WebhookForm("Slack", "ftp://example.com/hooks", null, List.of())))
                .isInstanceOf(NameNotAllowedException.class);
    }

    @Test
    void createWebhookRejectsUnknownEventType() {
        assertThatThrownBy(() -> service.createWebhook(new WebhookForm("Slack", "https://example.com/hooks", null,
                List.of("BOGUS"))))
                .isInstanceOf(NameNotAllowedException.class);
    }

    @Test
    void toggleWebhookEnablesAndAudits() {
        when(webhookConfigRepository.findById("w1")).thenReturn(Optional.of(webhook("w1", "Slack", false)));

        service.toggleWebhook("w1");

        ArgumentCaptor<WebhookConfig> captor = ArgumentCaptor.forClass(WebhookConfig.class);
        verify(webhookConfigRepository).save(captor.capture());
        assertThat(captor.getValue().isEnabled()).isTrue();
        ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getEventType()).isEqualTo(AuditEvent.WEBHOOK_UPDATED);
        assertThat(auditCaptor.getValue().getUserName()).isEqualTo("Slack");
    }

    @Test
    void toggleMissingWebhookThrowsNotFound() {
        when(webhookConfigRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.toggleWebhook("missing"))
                .isInstanceOf(WebhookNotFoundException.class);
    }

    @Test
    void deleteWebhookRemovesAndAudits() {
        when(webhookConfigRepository.findById("w1")).thenReturn(Optional.of(webhook("w1", "Slack", true)));

        service.deleteWebhook("w1");

        verify(webhookConfigRepository).deleteById("w1");
        ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getEventType()).isEqualTo(AuditEvent.WEBHOOK_DELETED);
    }
}
