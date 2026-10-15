package com.example.intelligent_cv_matching_ai_agent.controller.admin.rag;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.rag.CourseLinkRequest;
import com.example.intelligent_cv_matching_ai_agent.service.admin.rag.RagCourseService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/rag/courses")
public class RagCourseController {

    private final RagCourseService service;

    public RagCourseController(
        RagCourseService service) {
        this.service = service;
    }

    @GetMapping
    public Object list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public Object get(
        @PathVariable
        UUID id) {
        return service.get(id);
    }

    @PostMapping
    public Object create(
        @Valid
        @RequestBody
        CourseLinkRequest request) {
        return service.create(request);
    }

    @PostMapping("/{id}/approve")
    public Object approve(
        @PathVariable
        UUID id) {
        return service.approve(id);
    }

    @PostMapping("/{id}/archive")
    public Object archive(
        @PathVariable
        UUID id) {
        return service.archive(id);
    }

    @PostMapping("/{id}/index")
    public Object index(
        @PathVariable
        UUID id) {
        return service.index(id);
    }
}
