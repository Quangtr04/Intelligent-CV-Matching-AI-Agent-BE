package com.example.intelligent_cv_matching_ai_agent.controller.candidate.consultation;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.consultation.CreateConsultationRequest;
import com.example.intelligent_cv_matching_ai_agent.service.shared.consultation.ConsultationService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/consultations")
public class CandidateConsultationController {

    private final ConsultationService service;

    public CandidateConsultationController(
        ConsultationService service) {
        this.service = service;
    }

    @PostMapping
    public Map<String, Object> create(
        @Valid
        @RequestBody
        CreateConsultationRequest input) {
        return service.create(input);
    }
}
