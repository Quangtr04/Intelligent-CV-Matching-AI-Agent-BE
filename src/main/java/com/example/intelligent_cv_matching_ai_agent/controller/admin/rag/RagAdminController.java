package com.example.intelligent_cv_matching_ai_agent.controller.admin.rag;

import com.example.intelligent_cv_matching_ai_agent.service.admin.rag.RagDocumentService;

import org.springframework.web.bind.annotation.*;
import java.util.*;
import jakarta.validation.Valid;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.rag.CreateRagDocumentRequest;

@RestController
@RequestMapping("/api/v1/admin/rag/documents")
public class RagAdminController {

    private final RagDocumentService service;

    public RagAdminController(
        RagDocumentService service) {
        this.service = service;
    }

    @GetMapping
    public Object list() {
        return service.list();
    }

    @PostMapping
    public Object create(
        @Valid
        @RequestBody
        CreateRagDocumentRequest body) {
        return service.create(body.toInputMap());
    }

    @GetMapping("/{id}")
    public Object get(
        @PathVariable
        UUID id) {
        return service.get(id);
    }

    @PostMapping("/{id}/index")
    public Object index(
        @PathVariable
        UUID id) {
        return service.index(id);
    }

    @PostMapping("/{id}/archive")
    public Object archive(
        @PathVariable
        UUID id) {
        return service.archive(id);
    }
}
