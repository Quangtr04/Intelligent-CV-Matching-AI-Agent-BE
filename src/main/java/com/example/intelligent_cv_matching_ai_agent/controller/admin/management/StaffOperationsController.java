package com.example.intelligent_cv_matching_ai_agent.controller.admin.management;

import com.example.intelligent_cv_matching_ai_agent.service.admin.management.StaffOperationsService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/staff")
public class StaffOperationsController {

    private final StaffOperationsService service;

    public StaffOperationsController(
        StaffOperationsService service) {
        this.service = service;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return service.dashboard();
    }

    @GetMapping("/consultants")
    public List<Map<String, Object>> consultants(
        @RequestParam(defaultValue = "")
        String search,
        @RequestParam(defaultValue = "20")
        int limit,
        @RequestParam(defaultValue = "0")
        int offset) {
        return service.consultants(search, limit, offset);
    }

    @GetMapping("/consultants/{id}/ratings")
    public List<Map<String, Object>> consultantRatingsByConsultant(
        @PathVariable
        UUID id,
        @RequestParam(required = false)
        Integer rating,
        @RequestParam(defaultValue = "false")
        boolean includeRemoved,
        @RequestParam(defaultValue = "20")
        int limit,
        @RequestParam(defaultValue = "0")
        int offset) {
        return service.consultantRatings(id, rating, includeRemoved, limit, offset);
    }

    @GetMapping("/consultant-ratings")
    public List<Map<String, Object>> consultantRatings(
        @RequestParam(required = false)
        UUID consultantId,
        @RequestParam(required = false)
        Integer rating,
        @RequestParam(defaultValue = "false")
        boolean includeRemoved,
        @RequestParam(defaultValue = "20")
        int limit,
        @RequestParam(defaultValue = "0")
        int offset) {
        return service.consultantRatings(consultantId, rating, includeRemoved, limit, offset);
    }

    @GetMapping("/feedback")
    public List<Map<String, Object>> feedback(
        @RequestParam(required = false)
        String source,
        @RequestParam(defaultValue = "20")
        int limit,
        @RequestParam(defaultValue = "0")
        int offset) {
        return service.feedback(source, limit, offset);
    }

    @GetMapping("/crawl-reports")
    public List<Map<String, Object>> crawlReports(
        @RequestParam(required = false)
        String source,
        @RequestParam(required = false)
        String status,
        @RequestParam(defaultValue = "20")
        int limit,
        @RequestParam(defaultValue = "0")
        int offset) {
        return service.crawlReports(source, status, limit, offset);
    }
}
