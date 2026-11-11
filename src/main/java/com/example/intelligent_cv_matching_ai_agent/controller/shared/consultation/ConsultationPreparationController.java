package com.example.intelligent_cv_matching_ai_agent.controller.shared.consultation;

import com.example.intelligent_cv_matching_ai_agent.service.shared.consultation.ConsultationPreparationService;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/consultation-bookings")
public class ConsultationPreparationController {

    private final ConsultationPreparationService service;

    public ConsultationPreparationController(
        ConsultationPreparationService service) {
        this.service = service;
    }

    @GetMapping("/{id}/preparation")
    public Map<String, Object> preparation(
        @PathVariable
        UUID id) {
        return service.preparation(id);
    }
}
