package com.example.intelligent_cv_matching_ai_agent.controller.candidate.jobs;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.jobs.ApplicationDraftUpdateRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.jobs.CvRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.jobs.DraftRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.jobs.MatchRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.jobs.MatchingFeedbackRequest;

import com.example.intelligent_cv_matching_ai_agent.service.candidate.jobs.CareerToolsService;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class CareerToolsController {

    private final CareerToolsService service;

    public CareerToolsController(
        CareerToolsService service) {
        this.service = service;
    }

    @PostMapping("/matching")
    public Map<String, Object> match(
        @Valid
        @RequestBody
        MatchRequest input) {
        return service.match(input);
    }

    @GetMapping("/matching/{id}")
    public Map<String, Object> run(
        @PathVariable
        UUID id) {
        return service.run(id);
    }

    @GetMapping("/matching")
    public List<Map<String, Object>> history(
        @RequestParam(defaultValue = "20")
        int limit) {
        return service.history(limit);
    }

    @PutMapping("/matching/results/{id}/feedback")
    public Map<String, Object> feedback(
        @PathVariable
        UUID id,
        @Valid
        @RequestBody
        MatchingFeedbackRequest input) {
        return service.feedback(id, input);
    }

    @PostMapping("/jobs/{jobId}/ats")
    public Map<String, Object> ats(
        @PathVariable
        UUID jobId,
        @Valid
        @RequestBody
        CvRequest input) {
        return service.ats(jobId, input);
    }

    @PostMapping("/jobs/{jobId}/resume-draft")
    public Map<String, Object> targetedResume(
        @PathVariable
        UUID jobId,
        @RequestBody
        Map<String, Object> input) {
        return service.targetedResume(jobId, input);
    }

    @PostMapping("/jobs/{jobId}/application-drafts")
    public List<Map<String, Object>> createDraft(
        @PathVariable
        UUID jobId,
        @Valid
        @RequestBody
        DraftRequest input) {
        return service.createDraft(jobId, input);
    }

    @GetMapping("/application-drafts")
    public List<Map<String, Object>> drafts(
        @RequestParam(required = false)
        UUID jobId) {
        return service.drafts(jobId);
    }

    @PatchMapping("/application-drafts/{id}")
    public Map<String, Object> editDraft(
        @PathVariable
        UUID id,
        @Valid
        @RequestBody
        ApplicationDraftUpdateRequest input) {
        return service.editDraft(id, input);
    }

    @PostMapping("/jobs/{id}/external-apply")
    public Map<String, Object> apply(
        @PathVariable
        UUID id,
        @RequestBody(required = false)
        CvRequest input) {
        return service.apply(id, input);
    }

    @PostMapping("/jobs/{id}/salary-estimation")
    public Map<String, Object> salary(
        @PathVariable
        UUID id) {
        return service.salary(id);
    }
}
