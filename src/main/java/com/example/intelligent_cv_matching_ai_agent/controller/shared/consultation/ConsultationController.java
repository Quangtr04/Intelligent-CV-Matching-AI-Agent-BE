package com.example.intelligent_cv_matching_ai_agent.controller.shared.consultation;

import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.consultation.ConsultationMessageRequest;

import com.example.intelligent_cv_matching_ai_agent.service.shared.consultation.ConsultationService;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/consultations")
public class ConsultationController {

    private final ConsultationService service;

    public ConsultationController(
        ConsultationService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public Map<String, Object> thread(
        @PathVariable
        UUID id) {
        return service.thread(id);
    }

    @GetMapping("/{id}/messages")
    public List<Map<String, Object>> messages(
        @PathVariable
        UUID id,
        @RequestParam(defaultValue = "50")
        int limit,
        @RequestParam(defaultValue = "0")
        int offset) {
        return service.messages(id, limit, offset);
    }

    @PostMapping("/{id}/messages")
    public Map<String, Object> send(
        @PathVariable
        UUID id,
        @Valid
        @RequestBody
        ConsultationMessageRequest input) {
        return service.send(id, input);
    }

    @PatchMapping("/{id}/messages/{messageId}/read")
    public Map<String, Object> read(
        @PathVariable
        UUID id,
        @PathVariable
        UUID messageId) {
        return service.read(id, messageId);
    }

    @PostMapping("/{id}/close")
    public Map<String, Object> close(
        @PathVariable
        UUID id) {
        return service.close(id);
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
        @PathVariable
        UUID id) {
        return service.events(id);
    }
}
