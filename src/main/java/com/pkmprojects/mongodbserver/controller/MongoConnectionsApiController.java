package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import com.pkmprojects.mongodbserver.service.ProvisioningService;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Structured endpoint metadata for MongoDB, mirroring the
 * PostgreSQL connections API shape without pooling fiction — MongoDB has one
 * DIRECT path with an {@code authSource=dbName} contract. No passwords, no
 * secrets.
 */
@RestController
@RequestMapping("/api/mongo/databases")
@ConditionalOnProperty(name = "app.mongo.enabled", havingValue = "true")
public class MongoConnectionsApiController {
    private final ProvisioningService provisioningService;
    private final Optional<com.pkmprojects.mongodbserver.service.MongoDatabaseEngine> engine;

    public MongoConnectionsApiController(ProvisioningService provisioningService,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            com.pkmprojects.mongodbserver.service.MongoDatabaseEngine engine) {
        this.provisioningService = provisioningService;
        this.engine = Optional.ofNullable(engine);
    }

    @GetMapping("/{dbName}/connections")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    public ResponseEntity<?> connections(@PathVariable String dbName) {
        var md = provisioningService.findManagedDatabase(DatabaseEngineType.MONGO, dbName);
        if (md.isEmpty()) return ResponseEntity.notFound().build();
        if (engine.isEmpty()) return ResponseEntity.notFound().build();
        String host = engine.get().resolveConnectionHost();
        boolean tls = engine.get().resolveConnectionTls();
        return ResponseEntity.ok(Map.of(
                "database", dbName,
                "engine", DatabaseEngineType.MONGO.name(),
                "direct", Map.of("enabled", true, "host", host,
                        "database", dbName, "authSource", dbName, "tls", tls, "mode", "DIRECT")));
    }
}
