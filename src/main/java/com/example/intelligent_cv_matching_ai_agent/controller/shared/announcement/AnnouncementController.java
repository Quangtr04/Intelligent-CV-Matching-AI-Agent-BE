package com.example.intelligent_cv_matching_ai_agent.controller.shared.announcement;

import com.example.intelligent_cv_matching_ai_agent.service.shared.announcement.AnnouncementService;

import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class AnnouncementController {

    private final AnnouncementService service;

    public AnnouncementController(
        AnnouncementService service) {
        this.service = service;
    }

    @GetMapping("/announcements")
    public List<Map<String, Object>> visible() {
        return service.visible();
    }

    @GetMapping("/notifications")
    public List<Map<String, Object>> notifications(
        @RequestParam(defaultValue = "false")
        boolean unreadOnly) {
        return service.notifications(unreadOnly);
    }

    @PatchMapping("/notifications/{id}/read")
    public Map<String, Object> read(
        @PathVariable
        UUID id) {
        return service.read(id);
    }

    @PostMapping("/notifications/read-all")
    public Map<String, Object> readAll() {
        return service.readAll();
    }
}
