package com.example.intelligent_cv_matching_ai_agent.controller.candidate.learning;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.learning.AssessmentAnswerRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.learning.AssessmentRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.learning.RoadmapNodeStatusRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.learning.RoadmapRequest;

import com.example.intelligent_cv_matching_ai_agent.service.candidate.learning.LearningService;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class LearningController {

    private final LearningService service;

    public LearningController(
        LearningService service) {
        this.service = service;
    }

    @PostMapping("/assessments")
    public Map<String, Object> assess(
        @Valid
        @RequestBody
        AssessmentRequest input) {
        return service.assess(input);
    }

    @GetMapping("/assessments")
    public List<Map<String, Object>> assessments() {
        return service.assessments();
    }

    @GetMapping("/assessments/{id}")
    public Map<String, Object> assessment(
        @PathVariable
        UUID id) {
        return service.assessment(id);
    }

    @PutMapping("/assessments/{id}/answers")
    public Map<String, Object> answer(
        @PathVariable
        UUID id,
        @Valid
        @RequestBody
        AssessmentAnswerRequest input) {
        return service.answer(id, input);
    }

    @PostMapping("/assessments/{id}/submit")
    public Map<String, Object> submit(
        @PathVariable
        UUID id) {
        return service.submit(id);
    }

    @PostMapping("/roadmaps")
    public Map<String, Object> createRoadmap(
        @Valid
        @RequestBody
        RoadmapRequest input) {
        return service.createRoadmap(input);
    }

    @GetMapping("/roadmaps/recommendations")
    public Map<String, Object> recommendations(
        @RequestParam
        String targetJobTitle,
        @RequestParam(required = false)
        List<String> skills) {
        return service.recommendations(targetJobTitle, skills);
    }

    @GetMapping("/roadmaps")
    public List<Map<String, Object>> roadmaps(
        @RequestParam(required = false)
        UUID assessmentId) {
        return service.roadmaps(assessmentId);
    }

    @GetMapping("/roadmaps/{id}")
    public Map<String, Object> roadmap(
        @PathVariable
        UUID id) {
        return service.roadmap(id);
    }

    @PatchMapping("/roadmaps/{id}/nodes/{nodeId}")
    public Map<String, Object> node(
        @PathVariable
        UUID id,
        @PathVariable
        UUID nodeId,
        @Valid
        @RequestBody
        RoadmapNodeStatusRequest input) {
        return service.node(id, nodeId, input);
    }
}
