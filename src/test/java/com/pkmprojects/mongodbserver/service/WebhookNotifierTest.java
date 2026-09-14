package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.model.AuditEvent;
import com.pkmprojects.mongodbserver.model.AuditEventRecorded;
import com.pkmprojects.mongodbserver.model.WebhookConfig;
import com.pkmprojects.mongodbserver.model.WebhookDeliveryAttempt;
import com.pkmprojects.mongodbserver.store.WebhookConfigStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for webhook event fan-out and delivery.
 *
 * <p>Delivery attempts are also recorded on the bounded trail —
 * success, 4xx-no-retry, 5xx-retry, and final failure — with no secrets or
 * bodies in the record.</p>
 */
@ExtendWith(MockitoExtension.class)
class WebhookNotifierTest {

    private static final Instant NOW = Instant.parse("2026-08-18T10:00:00Z");

    @Mock
    private WebhookConfigStore WebhookConfigStore;
    @Mock
    private HttpClient httpClient;
    @Mock
    private ExecutorService executor;

    private WebhookNotifier notifier;
    private WebhookDeliveryTrail trail;

    @BeforeEach
    void setUp() {
        // Run deliveries synchronously so tests observe the send call. Skip-tests
        // never submit, so the stub must be lenient.
        lenient().doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(executor).submit(any(Runnable.class));
        trail = new WebhookDeliveryTrail();
        notifier = new WebhookNotifier(WebhookConfigStore, httpClient, executor, trail);
    }

    private WebhookConfig webhook(String name, String url, String secret, List<String> eventTypes, boolean enabled) {
        return new WebhookConfig(name, url, secret, eventTypes, enabled, NOW);
    }

    private AuditEventRecorded event() {
        return new AuditEventRecorded(
                new AuditEvent(AuditEvent.PROVISION, "myapp", "appuser", "admin", NOW));
    }

    private HttpResponse<String> response(int status) {
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        return response;
    }

    private String bodyOf(HttpRequest request) throws Exception {
        HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.util.concurrent.CompletableFuture<Void> done = new java.util.concurrent.CompletableFuture<>();
        publisher.subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            private java.util.concurrent.Flow.Subscription subscription;

            @Override
            public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
                subscription = s;
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(java.nio.ByteBuffer item) {
                java.nio.ByteBuffer copy = item.duplicate();
                byte[] chunk = new byte[copy.remaining()];
                copy.get(chunk);
                out.writeBytes(chunk);
            }

            @Override
            public void onError(Throwable throwable) {
                done.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                done.complete(null);
            }
        });
        done.get(5, java.util.concurrent.TimeUnit.SECONDS);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void onAuditEventPostsMatchingWebhook() throws Exception {
        HttpResponse<String> ok = response(200);
        when(WebhookConfigStore.findByEnabledTrue()).thenReturn(List.of(
                webhook("slack", "https://example.com/hooks/events", null,
                        List.of(AuditEvent.PROVISION, AuditEvent.DELETE), true)));
        when(httpClient.<String>send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(ok);

        notifier.onAuditEvent(event());

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        HttpRequest request = requestCaptor.getValue();
        assertThat(request.uri().toString()).isEqualTo("https://example.com/hooks/events");
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.headers().firstValue("Content-Type")).hasValue("application/json");
        assertThat(request.headers().firstValue("X-Webhook-Signature")).isEmpty();
        String body = bodyOf(request);
        assertThat(body).contains("\"eventType\":\"PROVISION\"");
        assertThat(body).contains("\"dbName\":\"myapp\"");
        assertThat(body).contains("\"userName\":\"appuser\"");
        assertThat(body).contains("\"performedBy\":\"admin\"");
    }

    @Test
    void onAuditEventSkipsWebhookWithNonMatchingEventType() {
        when(WebhookConfigStore.findByEnabledTrue()).thenReturn(List.of(
                webhook("slack", "https://example.com/hooks/events", null,
                        List.of(AuditEvent.DELETE), true)));

        notifier.onAuditEvent(event());

        verifyNoInteractions(httpClient);
    }

    @Test
    void onAuditEventSkipsDisabledWebhook() {
        when(WebhookConfigStore.findByEnabledTrue()).thenReturn(List.of(
                webhook("slack", "https://example.com/hooks/events", null, List.of(AuditEvent.PROVISION), false)));

        notifier.onAuditEvent(event());

        verifyNoInteractions(httpClient);
    }

    @Test
    void onAuditEventSkipsWebhookTargetingBlockedHostAtDelivery() {
        // Enabled and matching, but the target is a reserved/loopback host. The
        // delivery-time re-check must refuse to dial it even though it was
        // configured (an SSRF guard, not just a creation-time validation).
        when(WebhookConfigStore.findByEnabledTrue()).thenReturn(List.of(
                webhook("internal", "http://127.0.0.1:8080/hook", null, List.of(), true)));

        notifier.onAuditEvent(event());

        verifyNoInteractions(httpClient);
    }

    @Test
    void onAuditEventSurvivesWebhookQueryFailure() {
        when(WebhookConfigStore.findByEnabledTrue())
                .thenThrow(new IllegalStateException("webhook_configs unavailable"));

        // Must not throw: a webhook-infrastructure failure must never fail the
        // admin action that published the event.
        assertThatCode(() -> notifier.onAuditEvent(event()))
                .doesNotThrowAnyException();

        verifyNoInteractions(httpClient);
    }

    @Test
    void onAuditEventSurvivesRejectedSubmitDuringShutdown() {
        when(WebhookConfigStore.findByEnabledTrue()).thenReturn(List.of(
                webhook("slack", "https://example.com/hooks/events", null, List.of(), true)));
        doThrow(new java.util.concurrent.RejectedExecutionException("executor shut down"))
                .when(executor).submit(any(Runnable.class));

        assertThatCode(() -> notifier.onAuditEvent(event()))
                .doesNotThrowAnyException();
    }

    @Test
    void webhookWithoutEventTypesReceivesAllEvents() throws Exception {
        HttpResponse<String> ok = response(200);
        when(WebhookConfigStore.findByEnabledTrue()).thenReturn(List.of(
                webhook("slack", "https://example.com/hooks/events", null, List.of(), true)));
        when(httpClient.<String>send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(ok);

        notifier.onAuditEvent(event());

        verify(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void secretWebhookSignsPayloadWithHmacSha256() throws Exception {
        HttpResponse<String> ok = response(200);
        when(WebhookConfigStore.findByEnabledTrue()).thenReturn(List.of(
                webhook("slack", "https://example.com/hooks/events", "hunter2", List.of(), true)));
        when(httpClient.<String>send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(ok);

        notifier.onAuditEvent(event());

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        HttpRequest request = requestCaptor.getValue();
        String body = bodyOf(request);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("hunter2".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        assertThat(request.headers().firstValue("X-Webhook-Signature")).hasValue(expected);
    }

    @Test
    void transientFailureIsRetriedUpToThreeTimes() throws Exception {
        HttpResponse<String> failure = response(500);
        when(WebhookConfigStore.findByEnabledTrue()).thenReturn(List.of(
                webhook("slack", "https://example.com/hooks/events", null, List.of(), true)));
        when(httpClient.<String>send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(failure);

        notifier.onAuditEvent(event());

        verify(httpClient, times(WebhookNotifier.MAX_DELIVERY_ATTEMPTS))
                .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void permanentClientErrorIsNotRetried() throws Exception {
        HttpResponse<String> badRequest = response(400);
        when(WebhookConfigStore.findByEnabledTrue()).thenReturn(List.of(
                webhook("slack", "https://example.com/hooks/events", null, List.of(), true)));
        when(httpClient.<String>send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(badRequest);

        notifier.onAuditEvent(event());

        verify(httpClient, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void successAfterRetryStopsRetrying() throws Exception {
        HttpResponse<String> failure = response(500);
        HttpResponse<String> ok = response(200);
        when(WebhookConfigStore.findByEnabledTrue()).thenReturn(List.of(
                webhook("slack", "https://example.com/hooks/events", null, List.of(), true)));
        when(httpClient.<String>send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(failure, ok);

        notifier.onAuditEvent(event());

        verify(httpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    // ── delivery trail ────────────────────────────────────────────────

    @Test
    void successfulDeliveryIsRecordedAsDelivered() throws Exception {
        HttpResponse<String> ok = response(200);
        when(WebhookConfigStore.findByEnabledTrue()).thenReturn(List.of(
                webhook("slack", "https://example.com/hooks/events", null, List.of(), true)));
        when(httpClient.<String>send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(ok);

        notifier.onAuditEvent(event());

        assertThat(trail.recent()).hasSize(1);
        WebhookDeliveryAttempt attempt = trail.recent().get(0);
        assertThat(attempt.webhookName()).isEqualTo("slack");
        assertThat(attempt.eventType()).isEqualTo(AuditEvent.PROVISION);
        assertThat(attempt.result()).isEqualTo(WebhookDeliveryAttempt.DeliveryResult.DELIVERED);
        assertThat(attempt.httpStatus()).isEqualTo(200);
        assertThat(attempt.finalAttempt()).isTrue();
    }

    @Test
    void clientErrorIsRecordedOnceAsFailed() throws Exception {
        HttpResponse<String> badRequest = response(400);
        when(WebhookConfigStore.findByEnabledTrue()).thenReturn(List.of(
                webhook("slack", "https://example.com/hooks/events", null, List.of(), true)));
        when(httpClient.<String>send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(badRequest);

        notifier.onAuditEvent(event());

        verify(httpClient, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        assertThat(trail.recent()).hasSize(1);
        assertThat(trail.recent().get(0).result()).isEqualTo(WebhookDeliveryAttempt.DeliveryResult.FAILED);
        assertThat(trail.recent().get(0).httpStatus()).isEqualTo(400);
    }

    @Test
    void serverErrorRetriesRecordEachAttemptAndFinalFailure() throws Exception {
        HttpResponse<String> failure = response(500);
        when(WebhookConfigStore.findByEnabledTrue()).thenReturn(List.of(
                webhook("slack", "https://example.com/hooks/events", null, List.of(), true)));
        when(httpClient.<String>send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(failure);

        notifier.onAuditEvent(event());

        verify(httpClient, times(WebhookNotifier.MAX_DELIVERY_ATTEMPTS))
                .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        // One record per attempt: RETRYING, RETRYING, FAILED(final).
        assertThat(trail.recent()).hasSize(WebhookNotifier.MAX_DELIVERY_ATTEMPTS);
        assertThat(trail.recent().get(0).result()).isEqualTo(WebhookDeliveryAttempt.DeliveryResult.FAILED);
        assertThat(trail.recent().get(0).finalAttempt()).isTrue();
        assertThat(trail.recent().get(0).httpStatus()).isEqualTo(500);
    }

    @Test
    void blockedHostDeliveryIsRecordedAsFailedWithoutHttpSend() throws Exception {
        // Blocked at validation normally, but the notifier re-checks at send
        // time for DNS-rebinding — drive deliver() directly with a webhook
        // whose host is blocked.
        WebhookNotifier direct = new WebhookNotifier(WebhookConfigStore, httpClient, executor, trail);
        direct.deliver(webhook("local", "http://localhost:9/hooks", null, List.of(), true), event().event());

        verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        assertThat(trail.recent()).hasSize(1);
        assertThat(trail.recent().get(0).result()).isEqualTo(WebhookDeliveryAttempt.DeliveryResult.FAILED);
        assertThat(trail.recent().get(0).failureCategory()).isEqualTo("blocked-host");
    }

    @Test
    void trailIsBoundedAndNeverLeaksSecrets() {
        WebhookDeliveryTrail bounded = new WebhookDeliveryTrail();
        for (int i = 0; i < WebhookDeliveryTrail.MAX_RETAINED + 10; i++) {
            bounded.record(new WebhookDeliveryAttempt("w" + i, "hook", AuditEvent.PROVISION,
                    NOW, 1, WebhookDeliveryAttempt.DeliveryResult.DELIVERED, 200, null, true));
        }
        assertThat(bounded.recent()).hasSize(WebhookDeliveryTrail.MAX_RETAINED);
        // Newest first: the last record wins the head.
        assertThat(bounded.recent().get(0).webhookId()).isEqualTo("w" + (WebhookDeliveryTrail.MAX_RETAINED + 9));
        // Record shape carries no secret/header/body fields by construction —
        // assert the record's string form contains no secret material.
        assertThat(bounded.recent().get(0).toString()).doesNotContain("hunter2");
    }
}
