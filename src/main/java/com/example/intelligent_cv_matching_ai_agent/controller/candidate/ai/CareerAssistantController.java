package com.example.intelligent_cv_matching_ai_agent.controller.candidate.ai;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.ai.AssistantFeedbackRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.ai.CreateAssistantConversationRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.ai.AssistantMessageRequest;

import com.example.intelligent_cv_matching_ai_agent.service.candidate.ai.CareerAssistantService;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/assistant")
public class CareerAssistantController {

    private final CareerAssistantService service;

    public CareerAssistantController(
        CareerAssistantService service) {
        this.service = service;
    }

    @GetMapping("/conversations")
    public Object list() {
        return service.list();
    }

    @PostMapping("/conversations")
    public Object create(
        @Valid
        @RequestBody
        CreateAssistantConversationRequest body) {
        return service.create(body.toInputMap());
    }

    @GetMapping("/conversations/{id}")
    public Object get(
        @PathVariable
        UUID id) {
        return service.get(id);
    }

    @PostMapping("/conversations/{id}/messages")
    public Object send(
        @PathVariable
        UUID id,
        @Valid
        @RequestBody
        AssistantMessageRequest body) {
        return service.send(id, body.toInputMap());
    }

    @DeleteMapping("/conversations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(
        @PathVariable
        UUID id) {
        service.archive(id);
    }

    @PostMapping("/messages/{id}/feedback")
    public Object feedback(
        @PathVariable
        UUID id,
        @Valid
        @RequestBody
        AssistantFeedbackRequest body) {
        return service.feedback(id, body.toInputMap());
    }
}
