package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.model.AuditEvent;
import com.pkmprojects.mongodbserver.store.AuditStore;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * Full, paginated view of the admin activity audit trail with optional
 * server-side filtering (read-only).
 */
@Controller
public class ActivityController {

    /**
     * Page size for the audit-trail listing.
     */
    static final int PAGE_SIZE = 50;

    private final AuditStore auditStore;

    public ActivityController(AuditStore auditStore) {
        this.auditStore = auditStore;
    }

    /**
     * Renders one page of the audit trail, newest first, with optional filters.
     * Out-of-range pages clamp to the first page.
     */
    @GetMapping("/activity")
    public String activity(@RequestParam(name = "page", defaultValue = "1") int page,
                           @RequestParam(name = "eventType", required = false) String eventType,
                           @RequestParam(name = "engineType", required = false) String engineType,
                           @RequestParam(name = "dbName", required = false) String dbName,
                           @RequestParam(name = "userName", required = false) String userName,
                           @RequestParam(name = "performedBy", required = false) String performedBy,
                           Model model) {
        int safePage = Math.max(page, 1);

        long total = auditStore.countFiltered(eventType, engineType, dbName, userName, performedBy);
        int totalPages = Math.max((int) Math.ceil((double) total / PAGE_SIZE), 1);
        int safePageIndex = Math.min(safePage - 1, totalPages - 1);

        Sort sort = Sort.by(Sort.Direction.DESC, "performedAt");
        List<AuditEvent> events = auditStore.findFiltered(
                eventType, engineType, dbName, userName, performedBy,
                safePageIndex * PAGE_SIZE, PAGE_SIZE, sort);

        model.addAttribute("events", events);
        model.addAttribute("page", safePageIndex + 1);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("totalCount", total);
        model.addAttribute("hasPrev", safePageIndex > 0);
        model.addAttribute("hasNext", safePageIndex < totalPages - 1);
        model.addAttribute("eventType", eventType != null ? eventType : "");
        model.addAttribute("engineType", engineType != null ? engineType : "");
        model.addAttribute("dbName", dbName != null ? dbName : "");
        model.addAttribute("userName", userName != null ? userName : "");
        model.addAttribute("performedBy", performedBy != null ? performedBy : "");
        return "activity";
    }
}
