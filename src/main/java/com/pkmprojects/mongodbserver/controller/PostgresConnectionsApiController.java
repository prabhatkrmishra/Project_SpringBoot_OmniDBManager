package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import com.pkmprojects.mongodbserver.model.PublicConnectionEndpoint;
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
 * Structured endpoint metadata (§25). No passwords, no userlist secrets —
 * credential reveal stays a dedicated authed op (reset/detail decrypt path).
 */
@RestController
@RequestMapping("/api/postgres/databases")
@ConditionalOnProperty(name = "app.postgres.enabled", havingValue = "true")
public class PostgresConnectionsApiController {
    private final ProvisioningService provisioningService;
    private final Optional<com.pkmprojects.mongodbserver.service.PostgresDatabaseEngine> engine;

    public PostgresConnectionsApiController(ProvisioningService provisioningService,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            com.pkmprojects.mongodbserver.service.PostgresDatabaseEngine engine) {
        this.provisioningService = provisioningService;
        this.engine = Optional.ofNullable(engine);
    }

    @GetMapping("/{dbName}/connections")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    public ResponseEntity<?> connections(@PathVariable String dbName) {
        var md = provisioningService.findManagedDatabase(DatabaseEngineType.POSTGRES, dbName);
        if (md.isEmpty()) return ResponseEntity.notFound().build();
        boolean pooled = md.get().isPooled();
        // Public host/ports only — password never leaves the decrypt-on-view path.
        var conns = engine.map(e -> e.connectionEndpoints(dbName, md.get().getUserName(), "", pooled))
                .orElse(null);
        if (conns == null) return ResponseEntity.notFound().build();
        PublicConnectionEndpoint direct = conns.direct().withoutSecret();
        PublicConnectionEndpoint pooledEp = conns.pooled() == null ? null : conns.pooled().withoutSecret();
        boolean pooledHealthy = pooled && provisioningService.isPooledAuthInstalled(DatabaseEngineType.POSTGRES, dbName);
        return ResponseEntity.ok(Map.of(
                "database", dbName,
                "direct", Map.of("enabled", true, "host", direct.host(), "port", direct.port(),
                        "database", direct.database(), "sslMode", direct.sslMode().wireValue(), "mode", "DIRECT"),
                "pooled", pooledEp == null ? Map.of("enabled", false)
                        : Map.of("enabled", true, "host", pooledEp.host(), "port", pooledEp.port(),
                                "database", pooledEp.database(), "sslMode", pooledEp.sslMode().wireValue(),
                                "mode", "POOLED", "poolMode", "TRANSACTION", "authInstalled", pooledHealthy)));
    }
}
