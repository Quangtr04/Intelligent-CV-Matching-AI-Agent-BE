package com.example.intelligent_cv_matching_ai_agent.controller.guest.ai;

import com.example.intelligent_cv_matching_ai_agent.service.candidate.ai.CareerAssistantService;

import org.springframework.web.bind.annotation.*;
import java.util.*;
import jakarta.validation.Valid;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.ai.AssistantMessageRequest;

@RestController
@RequestMapping("/api/v1/guest/assistant")
public class GuestAssistantController {

    private final CareerAssistantService service;

    public GuestAssistantController(
        CareerAssistantService service) {
        this.service = service;
    }

    @PostMapping("/conversations")
    public Object create(
        @RequestHeader("X-Guest-Session")
        String key) {
        return service.createGuest(key);
    }

    @GetMapping("/conversations/{id}")
    public Object get(
        @PathVariable
        UUID id,
        @RequestHeader("X-Guest-Session")
        String key) {
        return service.getGuest(id, key);
    }

    @PostMapping("/conversations/{id}/messages")
    public Object send(
        @PathVariable
        UUID id,
        @Valid
        @RequestBody
        AssistantMessageRequest body,
        @RequestHeader("X-Guest-Session")
        String key) {
        return service.sendGuest(id, body.toInputMap(), key);
    }
}
