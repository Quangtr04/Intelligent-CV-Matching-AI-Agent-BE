package com.example.intelligent_cv_matching_ai_agent.controller.consultant.consultation;

import com.example.intelligent_cv_matching_ai_agent.dto.request.consultant.consultation.ConsultationAtsRequest;
import com.example.intelligent_cv_matching_ai_agent.service.consultant.consultation.ConsultantEvaluationService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/consultations")
public class ConsultantEvaluationController {

    private final ConsultantEvaluationService service;

    public ConsultantEvaluationController(
        ConsultantEvaluationService service) {
        this.service = service;
    }

    @Operation(summary = "List confirmed CVs of the candidate assigned to the career consultant")
    @GetMapping("/{id}/candidate-resumes")
    public List<Map<String, Object>> candidateResumes(
        @PathVariable
        UUID id) {
        return service.candidateResumes(id);
    }

    @Operation(summary = "Use AI to evaluate a candidate CV in an assigned, open consultation")
    @PostMapping("/{id}/resumes/{resumeId}/analyze")
    public Map<String, Object> analyzeResume(
        @PathVariable
        UUID id,
        @PathVariable
        UUID resumeId) {
        return service.analyzeResume(id, resumeId);
    }

    @Operation(summary = "Use AI to evaluate candidate CV suitability for a job in an assigned consultation")
    @PostMapping("/{id}/jobs/{jobId}/ats")
    public Map<String, Object> ats(
        @PathVariable
        UUID id,
        @PathVariable
        UUID jobId,
        @Valid
        @RequestBody
        ConsultationAtsRequest request) {
        return service.ats(id, jobId, request);
    }
}
