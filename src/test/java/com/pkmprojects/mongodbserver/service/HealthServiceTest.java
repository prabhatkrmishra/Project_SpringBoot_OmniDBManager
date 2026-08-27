package com.pkmprojects.mongodbserver.service;

import com.mongodb.MongoCommandException;
import com.mongodb.ServerAddress;
import com.pkmprojects.mongodbserver.dto.ServerHealth;
import com.pkmprojects.mongodbserver.repository.MongoDatabaseRepository;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the health dashboard assembly (mock repository).
 */
@ExtendWith(MockitoExtension.class)
class HealthServiceTest {

    @Mock
    private MongoDatabaseRepository mongoDatabaseRepository;

    private HealthService service;

    @BeforeEach
    void setUp() {
        service = new HealthService(mongoDatabaseRepository, null, false);
    }

    @Test
    void getHealthReportsReachableWithMetrics() {
        when(mongoDatabaseRepository.getServerStatus()).thenReturn(new Document("version", "7.0.39")
                .append("uptime", 90061)
                .append("connections", new Document("current", 3)));
        when(mongoDatabaseRepository.getDatabaseSizes())
                .thenReturn(Map.of("myapp", 1024L, "other", 2048L));

        ServerHealth health = service.getHealth();

        assertThat(health.reachable()).isTrue();
        assertThat(health.version()).isEqualTo("7.0.39");
        assertThat(health.uptimeSeconds()).isEqualTo(90061L);
        assertThat(health.connectionCount()).isEqualTo(3);
        assertThat(health.databaseCount()).isEqualTo(2);
        assertThat(health.totalStorageBytes()).isEqualTo(3072L);
    }

    @Test
    void getHealthReportsUnreachableWhenPingFails() {
        doThrow(mongoError(13, "Unauthorized")).when(mongoDatabaseRepository).ping();

        ServerHealth health = service.getHealth();

        assertThat(health.reachable()).isFalse();
        assertThat(health.version()).isNull();
        assertThat(health.uptimeSeconds()).isNull();
        assertThat(health.connectionCount()).isNull();
        assertThat(health.databaseCount()).isZero();
        assertThat(health.totalStorageBytes()).isNull();
    }

    @Test
    void getHealthDegradesMetricsWhenServerStatusUnauthorized() {
        doThrow(mongoError(13, "Unauthorized")).when(mongoDatabaseRepository).getServerStatus();
        when(mongoDatabaseRepository.getDatabaseSizes()).thenReturn(Map.of("myapp", 1024L));

        ServerHealth health = service.getHealth();

        assertThat(health.reachable()).isTrue();
        assertThat(health.version()).isNull();
        assertThat(health.uptimeSeconds()).isNull();
        assertThat(health.connectionCount()).isNull();
        assertThat(health.databaseCount()).isEqualTo(1);
        assertThat(health.totalStorageBytes()).isEqualTo(1024L);
    }

    @Test
    void getHealthDegradesStorageWhenListDatabasesFails() {
        when(mongoDatabaseRepository.getServerStatus()).thenReturn(new Document("version", "7.0.39"));
        doThrow(mongoError(13, "Unauthorized")).when(mongoDatabaseRepository).getDatabaseSizes();

        ServerHealth health = service.getHealth();

        assertThat(health.reachable()).isTrue();
        assertThat(health.version()).isEqualTo("7.0.39");
        assertThat(health.databaseCount()).isZero();
        assertThat(health.totalStorageBytes()).isNull();
    }

    @Test
    void uptimeFormatsHumanReadable() {
        assertThat(new ServerHealth(true, null, 45L, 0, null, null).uptime()).isEqualTo("45s");
        assertThat(new ServerHealth(true, null, 125L, 0, null, null).uptime()).isEqualTo("2m 5s");
        assertThat(new ServerHealth(true, null, 3661L, 0, null, null).uptime()).isEqualTo("1h 1m");
        assertThat(new ServerHealth(true, null, 90061L, 0, null, null).uptime()).isEqualTo("1d 1h 1m");
        assertThat(new ServerHealth(true, null, null, 0, null, null).uptime()).isNull();
    }

    @Test
    void storageLabelFormatsHumanReadable() {
        assertThat(new ServerHealth(true, null, null, 0, 512L, null).storageLabel()).isEqualTo("512 B");
        assertThat(new ServerHealth(true, null, null, 0, 2048L, null).storageLabel()).isEqualTo("2.0 KB");
        assertThat(new ServerHealth(true, null, null, 0, 5242880L, null).storageLabel()).isEqualTo("5.0 MB");
        assertThat(new ServerHealth(true, null, null, 0, 2147483648L, null).storageLabel()).isEqualTo("2.00 GB");
        assertThat(new ServerHealth(true, null, null, 0, null, null).storageLabel()).isNull();
    }

    private MongoCommandException mongoError(int code, String message) {
        return new MongoCommandException(
                new BsonDocument("ok", new BsonInt32(0))
                        .append("code", new BsonInt32(code))
                        .append("errmsg", new BsonString(message)),
                new ServerAddress("localhost", 27017));
    }
}