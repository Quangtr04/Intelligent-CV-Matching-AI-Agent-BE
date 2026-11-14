package com.example.intelligent_cv_matching_ai_agent.controller.admin.rag;

import com.example.intelligent_cv_matching_ai_agent.service.admin.rag.RagCourseService;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/rag/resources")
public class LearningResourceAdminController {

    private final RagCourseService service;

    public LearningResourceAdminController(
        RagCourseService service) {
        this.service = service;
    }

    @GetMapping
    public Object list() {
        return service.learningResources();
    }

    @PostMapping("/{id}/enable")
    public Object enable(
        @PathVariable
        UUID id) {
        return service.setResourceActive(id, true);
    }

    @PostMapping("/{id}/disable")
    public Object disable(
        @PathVariable
        UUID id) {
        return service.setResourceActive(id, false);
    }
}
