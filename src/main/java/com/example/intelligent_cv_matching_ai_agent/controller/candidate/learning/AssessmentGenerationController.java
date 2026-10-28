package com.example.intelligent_cv_matching_ai_agent.controller.candidate.learning;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.learning.AssessmentRequest;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.learning.AssessmentGenerationService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/assessments/generations")
public class AssessmentGenerationController {

    private final AssessmentGenerationService service;

    public AssessmentGenerationController(
        AssessmentGenerationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> start(
        @Valid
        @RequestBody
        AssessmentRequest input) {
        return ResponseEntity.accepted().body(service.start(input));
    }

    @GetMapping
    public List<Map<String, Object>> latest(
        @RequestParam(required = false)
        UUID jobId) {
        return service.latest(jobId);
    }

    @GetMapping("/{id}")
    public Map<String, Object> status(
        @PathVariable
        UUID id) {
        return service.status(id);
    }
}
