package com.example.intelligent_cv_matching_ai_agent.controller.consultant.consultation;

import com.example.intelligent_cv_matching_ai_agent.service.shared.consultation.ConsultationService;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/consultations")
public class ConsultantConsultationController {

    private final ConsultationService service;

    public ConsultantConsultationController(
        ConsultationService service) {
        this.service = service;
    }

    @GetMapping("/{id}/candidate-profile")
    public Map<String, Object> candidateProfile(
        @PathVariable
        UUID id,
        @RequestParam(required = false)
        UUID resumeId) {
        return service.candidateProfile(id, resumeId);
    }
}
