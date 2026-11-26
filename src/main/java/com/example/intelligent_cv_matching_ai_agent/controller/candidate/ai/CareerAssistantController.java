package com.example.intelligent_cv_matching_ai_agent.controller.candidate.ai;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.ai.AssistantFeedbackRequest;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.CandidateAiTaskGate;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.ai.CreateAssistantConversationRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.ai.UpdateAssistantConversationRequest;
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

    private final CandidateAiTaskGate gate;

    public CareerAssistantController(
        CandidateAiTaskGate gate,
        CareerAssistantService service) {
        this.service = service;
        this.gate = gate;
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

    @PatchMapping("/conversations/{id}")
    public Object update(
        @PathVariable
        UUID id,
        @Valid
        @RequestBody
        UpdateAssistantConversationRequest body) {
        return service.update(id, body.toInputMap());
    }

    @PostMapping("/conversations/{id}/messages")
    public Object send(
        @PathVariable
        UUID id,
        @Valid
        @RequestBody
        AssistantMessageRequest body) {
        return gate.run("CAREER_CHAT", () -> service.send(id, body.toInputMap()));
    }

    @DeleteMapping("/conversations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(
        @PathVariable
        UUID id) {
        service.archive(id);
    }

    @DeleteMapping("/conversations/{id}/messages")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearMessages(
        @PathVariable
        UUID id) {
        service.clearMessages(id);
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

    @DeleteMapping("/messages/{id}/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearFeedback(
        @PathVariable
        UUID id) {
        service.clearFeedback(id);
    }
}
