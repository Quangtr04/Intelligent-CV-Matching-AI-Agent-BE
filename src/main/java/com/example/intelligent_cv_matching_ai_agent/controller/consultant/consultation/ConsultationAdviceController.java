package com.example.intelligent_cv_matching_ai_agent.controller.consultant.consultation;

import com.example.intelligent_cv_matching_ai_agent.dto.request.consultant.consultation.ConsultationAdviceRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.consultant.consultation.ConsultationPrivateNotesRequest;
import com.example.intelligent_cv_matching_ai_agent.service.consultant.consultation.ConsultationAdviceService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/consultations")
public class ConsultationAdviceController {

    private final ConsultationAdviceService service;

    public ConsultationAdviceController(
        ConsultationAdviceService service) {
        this.service = service;
    }

    @Operation(summary = "Read assigned consultation advice; candidates receive published reports only")
    @GetMapping("/{id}/advice")
    public Map<String, Object> advice(
        @PathVariable
        UUID id) {
        return service.advice(id);
    }

    @Operation(summary = "Save or publish structured advice without sending a chat message")
    @PutMapping("/{id}/advice")
    public Map<String, Object> saveAdvice(
        @PathVariable
        UUID id,
        @Valid
        @RequestBody
        ConsultationAdviceRequest request) {
        return service.saveAdvice(id, request);
    }

    @Operation(summary = "Read private notes belonging to the assigned consultant")
    @GetMapping("/{id}/private-notes")
    public Map<String, Object> privateNotes(
        @PathVariable
        UUID id) {
        return service.privateNotes(id);
    }

    @Operation(summary = "Save assigned consultant private notes and a bounded assessment rubric")
    @PutMapping("/{id}/private-notes")
    public Map<String, Object> savePrivateNotes(
        @PathVariable
        UUID id,
        @Valid
        @RequestBody
        ConsultationPrivateNotesRequest request) {
        return service.savePrivateNotes(id, request);
    }
}
