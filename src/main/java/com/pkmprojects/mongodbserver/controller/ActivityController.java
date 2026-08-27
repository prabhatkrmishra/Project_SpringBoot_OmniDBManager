package com.pkmprojects.mongodbserver.controller;

import com.pkmprojects.mongodbserver.model.AuditEvent;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

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

    private final MongoTemplate mongoTemplate;

    public ActivityController(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
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

        Query query = buildFilterQuery(eventType, engineType, dbName, userName, performedBy);
        long total = mongoTemplate.count(query, AuditEvent.class);
        int totalPages = Math.max((int) Math.ceil((double) total / PAGE_SIZE), 1);
        int safePageIndex = Math.min(safePage - 1, totalPages - 1);

        query.with(PageRequest.of(safePageIndex, PAGE_SIZE, Sort.by(Sort.Direction.DESC, "performedAt")));
        List<AuditEvent> events = mongoTemplate.find(query, AuditEvent.class);

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

    /**
     * Builds a MongoDB query from optional filter parameters. Text fields use
     * case-insensitive "contains" matching with regex-escaped input.
     */
    private Query buildFilterQuery(String eventType, String engineType, String dbName, String userName, String performedBy) {
        List<Criteria> criteria = new ArrayList<>();
        if (eventType != null && !eventType.isBlank()) {
            criteria.add(Criteria.where("eventType").is(eventType.trim()));
        }
        if (engineType != null && !engineType.isBlank()) {
            criteria.add(Criteria.where("engineType").is(engineType.trim()));
        }
        if (dbName != null && !dbName.isBlank()) {
            criteria.add(Criteria.where("dbName").regex(containsPattern(dbName.trim()), "i"));
        }
        if (userName != null && !userName.isBlank()) {
            criteria.add(Criteria.where("userName").regex(containsPattern(userName.trim()), "i"));
        }
        if (performedBy != null && !performedBy.isBlank()) {
            criteria.add(Criteria.where("performedBy").regex(containsPattern(performedBy.trim()), "i"));
        }
        if (criteria.isEmpty()) {
            return new Query();
        }
        return new Query(Criteria.where("id").exists(true).andOperator(criteria.toArray(new Criteria[0])));
    }

    /**
     * Produces a regex pattern that matches any string containing {@code input}
     * (case-insensitive). Special regex characters in {@code input} are escaped
     * via {@link Pattern#quote} so user-supplied dots, stars etc. are literal.
     */
    private static String containsPattern(String input) {
        return ".*" + Pattern.quote(input) + ".*";
    }
}
