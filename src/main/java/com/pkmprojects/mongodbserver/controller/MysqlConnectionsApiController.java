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
 * Structured endpoint metadata for MySQL, mirroring the
 * PostgreSQL connections API shape without pooling fiction — MySQL has one
 * DIRECT path. No passwords, no secrets; credential reveal stays a
 * dedicated authed op.
 */
@RestController
@RequestMapping("/api/mysql/databases")
@ConditionalOnProperty(name = "app.mysql.enabled", havingValue = "true")
public class MysqlConnectionsApiController {
    private final ProvisioningService provisioningService;
    private final Optional<com.pkmprojects.mongodbserver.service.MysqlDatabaseEngine> engine;

    public MysqlConnectionsApiController(ProvisioningService provisioningService,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            com.pkmprojects.mongodbserver.service.MysqlDatabaseEngine engine) {
        this.provisioningService = provisioningService;
        this.engine = Optional.ofNullable(engine);
    }

    @GetMapping("/{dbName}/connections")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    public ResponseEntity<?> connections(@PathVariable String dbName) {
        var md = provisioningService.findManagedDatabase(DatabaseEngineType.MYSQL, dbName);
        if (md.isEmpty()) return ResponseEntity.notFound().build();
        if (engine.isEmpty()) return ResponseEntity.notFound().build();
        String host = engine.get().resolveHost();
        boolean tls = engine.get().isTls();
        return ResponseEntity.ok(Map.of(
                "database", dbName,
                "engine", DatabaseEngineType.MYSQL.name(),
                "direct", Map.of("enabled", true, "host", host,
                        "database", dbName, "tls", tls, "mode", "DIRECT")));
    }
}
