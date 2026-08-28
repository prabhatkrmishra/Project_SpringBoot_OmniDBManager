package com.pkmprojects.mongodbserver.config;

import com.pkmprojects.mongodbserver.repository.PostgresDatabaseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PostgresHealthIndicatorTest {

    @Mock
    private PostgresDatabaseRepository repository;

    @Test
    void healthUpWhenPingSucceeds() {
        doNothing().when(repository).ping();
        when(repository.getVersion()).thenReturn("18.6");

        PostgresHealthIndicator indicator = new PostgresHealthIndicator(repository);
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("reachable", true);
        assertThat(health.getDetails()).containsEntry("version", "18.6");
    }

    @Test
    void healthUpWithoutVersionWhenGetVersionFails() {
        doNothing().when(repository).ping();
        when(repository.getVersion()).thenThrow(new RuntimeException("no version"));

        PostgresHealthIndicator indicator = new PostgresHealthIndicator(repository);
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("reachable", true);
        assertThat(health.getDetails()).doesNotContainKey("version");
    }

    @Test
    void healthUpWithNullVersionOmitsVersionDetail() {
        doNothing().when(repository).ping();
        when(repository.getVersion()).thenReturn(null);

        PostgresHealthIndicator indicator = new PostgresHealthIndicator(repository);
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).doesNotContainKey("version");
    }

    @Test
    void healthDownWhenPingFails() {
        doThrow(new RuntimeException("connection refused")).when(repository).ping();

        PostgresHealthIndicator indicator = new PostgresHealthIndicator(repository);
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("reachable", false);
    }

    @Test
    void healthDownDoesNotCallGetVersion() {
        doThrow(new RuntimeException("down")).when(repository).ping();

        PostgresHealthIndicator indicator = new PostgresHealthIndicator(repository);
        indicator.health();

        verify(repository, never()).getVersion();
    }
}
