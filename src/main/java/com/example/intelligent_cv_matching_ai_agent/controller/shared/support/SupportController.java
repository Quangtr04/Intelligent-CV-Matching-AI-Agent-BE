package com.example.intelligent_cv_matching_ai_agent.controller.shared.support;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.support.SupportTicketRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.support.SupportMessageRequest;

import com.example.intelligent_cv_matching_ai_agent.service.shared.support.SupportService;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/support/tickets")
public class SupportController {

    private final SupportService service;

    public SupportController(
        SupportService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return service.list();
    }

    @PostMapping
    public Map<String, Object> create(
        @Valid
        @RequestBody
        SupportTicketRequest body) {
        return service.create(body);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(
        @PathVariable
        UUID id) {
        return service.detail(id);
    }

    @PostMapping("/{id}/messages")
    public Map<String, Object> message(
        @PathVariable
        UUID id,
        @Valid
        @RequestBody
        SupportMessageRequest body) {
        return service.message(id, body);
    }
}
