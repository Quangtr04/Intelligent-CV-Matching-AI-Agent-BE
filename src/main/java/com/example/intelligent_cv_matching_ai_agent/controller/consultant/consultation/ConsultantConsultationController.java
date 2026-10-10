package com.example.intelligent_cv_matching_ai_agent.controller.consultant.consultation;

import com.example.intelligent_cv_matching_ai_agent.dto.request.consultant.consultation.ConsultationAnalysisRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.response.consultant.consultation.ConsultationAnalysesResponse;
import com.example.intelligent_cv_matching_ai_agent.service.consultant.consultation.ConsultationAnalysisService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.consultation.ConsultationService;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/consultations")
public class ConsultantConsultationController {

    private final ConsultationService service;

    private final ConsultationAnalysisService analysisService;

    public ConsultantConsultationController(
        ConsultationService service,
        ConsultationAnalysisService analysisService) {
        this.service = service;
        this.analysisService = analysisService;
    }

    @GetMapping("/{id}/candidate-profile")
    public Map<String, Object> candidateProfile(
        @PathVariable
        UUID id,
        @RequestParam(required = false)
        UUID resumeId) {
        return service.candidateProfile(id, resumeId);
    }

    @GetMapping("/{id}/candidate-analyses")
    public ConsultationAnalysesResponse candidateAnalyses(
        @PathVariable
        UUID id,
        @Valid
        @ModelAttribute
        @ParameterObject
        ConsultationAnalysisRequest request) {
        return analysisService.candidateAnalyses(id, request);
    }
}
