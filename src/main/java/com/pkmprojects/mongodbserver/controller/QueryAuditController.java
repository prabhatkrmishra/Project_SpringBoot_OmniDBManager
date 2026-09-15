package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.audit.QueryAuditStore;
import com.pkmprojects.mongodbserver.audit.QueryAuditStore.QueryAuditFilter;
import com.pkmprojects.mongodbserver.audit.collector.AuditCollectorService;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Operator query-activity view: paginated, server-side filtered access to the
 * normalized/redacted {@code query_audit} trail. Reuses the activity-page
 * pagination pattern. Attribution (authoritative vs inferred) is shown
 * explicitly, especially for pooled PostgreSQL traffic.
 */
@Controller
public class QueryAuditController {

    static final int PAGE_SIZE = 50;

    private final QueryAuditStore queryAuditStore;
    private final AuditCollectorService collector;
    private final com.pkmprojects.mongodbserver.audit.collector.AuditTailRunner tails;

    public QueryAuditController(QueryAuditStore queryAuditStore,
                                @Autowired(required = false) AuditCollectorService collector,
                                @Autowired(required = false) com.pkmprojects.mongodbserver.audit.collector.AuditTailRunner tails) {
        this.queryAuditStore = queryAuditStore;
        this.collector = collector;
        this.tails = tails;
    }

    @GetMapping("/query-activity")
    @PreAuthorize("hasRole('ADMIN')")
    public String queryActivity(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "engine", required = false) String engine,
            @RequestParam(name = "database", required = false) String database,
            @RequestParam(name = "user", required = false) String user,
            @RequestParam(name = "sourceIp", required = false) String sourceIp,
            @RequestParam(name = "operationClass", required = false) String operationClass,
            @RequestParam(name = "success", required = false) String success,
            @RequestParam(name = "attribution", required = false) String attribution,
            @RequestParam(name = "confidence", required = false) String confidence,
            @RequestParam(name = "shapeHash", required = false) String shapeHash,
            Model model) {
        int safePage = Math.max(page, 1);
        Boolean successBool = "true".equalsIgnoreCase(success) ? Boolean.TRUE
                : "false".equalsIgnoreCase(success) ? Boolean.FALSE : null;
        QueryAuditFilter filter = new QueryAuditFilter(
                blank(engine), blank(database), blank(user), blank(sourceIp),
                blank(operationClass), successBool, blank(attribution),
                blank(confidence), blank(shapeHash), null, null);

        long total = queryAuditStore.countFiltered(filter);
        int totalPages = Math.max((int) Math.ceil((double) total / PAGE_SIZE), 1);
        int safePageIndex = Math.min(safePage - 1, totalPages - 1);

        var events = queryAuditStore.findFiltered(filter, safePageIndex * PAGE_SIZE, PAGE_SIZE);

        model.addAttribute("events", events);
        model.addAttribute("page", safePageIndex + 1);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("totalCount", total);
        model.addAttribute("hasPrev", safePageIndex > 0);
        model.addAttribute("hasNext", safePageIndex < totalPages - 1);
        model.addAttribute("engineFilter", engine != null ? engine : "");
        model.addAttribute("databaseFilter", database != null ? database : "");
        model.addAttribute("userFilter", user != null ? user : "");
        model.addAttribute("sourceIp", sourceIp != null ? sourceIp : "");
        model.addAttribute("operationClass", operationClass != null ? operationClass : "");
        model.addAttribute("success", success != null ? success : "");
        model.addAttribute("attribution", attribution != null ? attribution : "");
        model.addAttribute("confidence", confidence != null ? confidence : "");
        model.addAttribute("shapeHash", shapeHash != null ? shapeHash : "");
        model.addAttribute("collectorStatus", collector != null ? collector.status() : null);
        model.addAttribute("tailStatus", tails != null ? tails.status() : null);
        model.addAttribute("sort", Sort.by(Sort.Direction.DESC, "observedAt"));
        return "query-activity";
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Parses an optional ISO-8601 instant; blank/invalid yields null. */
    static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
