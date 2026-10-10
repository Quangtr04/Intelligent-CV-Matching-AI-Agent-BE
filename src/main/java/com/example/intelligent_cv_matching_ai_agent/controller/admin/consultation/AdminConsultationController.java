package com.example.intelligent_cv_matching_ai_agent.controller.admin.consultation;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.consultation.AssignConsultantRequest;
import com.example.intelligent_cv_matching_ai_agent.service.shared.consultation.ConsultationService;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/consultations")
public class AdminConsultationController {

    private final ConsultationService service;

    public AdminConsultationController(
        ConsultationService service) {
        this.service = service;
    }

    @PutMapping("/{id}/consultant")
    public Map<String, Object> assign(
        @PathVariable
        UUID id,
        @Valid
        @RequestBody
        AssignConsultantRequest input) {
        return service.assign(id, input);
    }
}
