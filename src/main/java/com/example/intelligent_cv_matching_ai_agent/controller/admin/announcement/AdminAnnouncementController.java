package com.example.intelligent_cv_matching_ai_agent.controller.admin.announcement;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.announcement.AnnouncementRequest;
import com.example.intelligent_cv_matching_ai_agent.service.shared.announcement.AnnouncementService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/announcements")
public class AdminAnnouncementController {

    private final AnnouncementService service;

    public AdminAnnouncementController(
        AnnouncementService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> all() {
        return service.all();
    }

    @PostMapping
    public Map<String, Object> create(
        @Valid
        @RequestBody
        AnnouncementRequest body) {
        return service.create(body);
    }

    @PutMapping("/{id}")
    public Map<String, Object> edit(
        @PathVariable
        UUID id,
        @Valid
        @RequestBody
        AnnouncementRequest body) {
        return service.edit(id, body);
    }

    @PostMapping("/{id}/publish")
    public Map<String, Object> publish(
        @PathVariable
        UUID id) {
        return service.publish(id);
    }

    @PostMapping("/{id}/archive")
    public Map<String, Object> archive(
        @PathVariable
        UUID id) {
        return service.archive(id);
    }
}
