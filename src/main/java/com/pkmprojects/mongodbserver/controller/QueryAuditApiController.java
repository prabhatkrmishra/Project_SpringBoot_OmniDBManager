package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.audit.QueryAuditEvent;
import com.pkmprojects.mongodbserver.audit.QueryAuditStore;
import com.pkmprojects.mongodbserver.audit.QueryAuditStore.QueryAuditFilter;
import com.pkmprojects.mongodbserver.audit.collector.AuditCollectorService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN-only JSON access to the query-activity trail. Normalized shapes only;
 * no raw query text exists in storage, so none can leak here.
 */
@RestController
@RequestMapping("/api/admin/query-activity")
public class QueryAuditApiController {

    private final QueryAuditStore queryAuditStore;
    private final AuditCollectorService collector;
    private final com.pkmprojects.mongodbserver.audit.collector.AuditTailRunner tails;

    public QueryAuditApiController(QueryAuditStore queryAuditStore,
                                   @Autowired(required = false) AuditCollectorService collector,
                                   @Autowired(required = false) com.pkmprojects.mongodbserver.audit.collector.AuditTailRunner tails) {
        this.queryAuditStore = queryAuditStore;
        this.collector = collector;
        this.tails = tails;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> list(
            @RequestParam(name = "engine", required = false) String engine,
            @RequestParam(name = "database", required = false) String database,
            @RequestParam(name = "user", required = false) String user,
            @RequestParam(name = "sourceIp", required = false) String sourceIp,
            @RequestParam(name = "operationClass", required = false) String operationClass,
            @RequestParam(name = "success", required = false) Boolean success,
            @RequestParam(name = "attribution", required = false) String attribution,
            @RequestParam(name = "confidence", required = false) String confidence,
            @RequestParam(name = "shapeHash", required = false) String shapeHash,
            @RequestParam(name = "skip", defaultValue = "0") int skip,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        QueryAuditFilter filter = new QueryAuditFilter(
                blank(engine), blank(database), blank(user), blank(sourceIp),
                blank(operationClass), success, blank(attribution),
                blank(confidence), blank(shapeHash), null, null);
        int safeSkip = Math.max(0, skip);
        int safeLimit = Math.max(1, Math.min(limit, 200));
        List<QueryAuditEvent> events = queryAuditStore.findFiltered(filter, safeSkip, safeLimit);
        long total = queryAuditStore.countFiltered(filter);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", total);
        body.put("skip", safeSkip);
        body.put("limit", safeLimit);
        body.put("events", events.stream().map(this::render).toList());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collector", collector != null ? collector.status() : Map.of("enabled", false));
        body.put("tails", tails != null ? tails.status() : Map.of("running", false));
        body.put("dropped", queryAuditStore.droppedCount());
        body.put("latest", queryAuditStore.latestObservedAt().map(Object::toString).orElse(null));
        return ResponseEntity.ok(body);
    }

    private Map<String, Object> render(QueryAuditEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventId", e.getEventId());
        m.put("observedAt", e.getObservedAt() == null ? null : e.getObservedAt().toString());
        m.put("engine", e.getEngine() == null ? null : e.getEngine().name());
        m.put("database", e.getDatabase());
        m.put("provisionedUser", e.getProvisionedUser());
        m.put("sourceIp", e.getSourceIp());
        m.put("operationClass", e.getOperationClass());
        m.put("commandType", e.getCommandType());
        m.put("normalizedShape", e.getNormalizedShape());
        m.put("shapeHash", e.getShapeHash());
        m.put("durationMs", e.getDurationMs());
        m.put("rowsAffected", e.getRowsAffected());
        m.put("rowsReturned", e.getRowsReturned());
        m.put("success", e.getSuccess());
        m.put("errorCode", e.getErrorCode());
        m.put("errorClass", e.getErrorClass());
        m.put("observationSource", e.getObservationSource() == null ? null : e.getObservationSource().name());
        m.put("attribution", e.getAttribution() == null ? null : e.getAttribution().name());
        m.put("auditConfidence", e.getAuditConfidence() == null ? null : e.getAuditConfidence().name());
        m.put("sessionId", e.getSessionId());
        return m;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
