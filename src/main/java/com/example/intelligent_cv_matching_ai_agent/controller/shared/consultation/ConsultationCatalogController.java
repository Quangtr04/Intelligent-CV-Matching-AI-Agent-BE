package com.example.intelligent_cv_matching_ai_agent.controller.shared.consultation;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.consultation.ConsultationServiceRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.consultation.ConsultationOrderRequest;
import com.example.intelligent_cv_matching_ai_agent.service.shared.consultation.ConsultationCatalogService;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ConsultationCatalogController {

    private final ConsultationCatalogService service;

    public ConsultationCatalogController(
        ConsultationCatalogService service) {
        this.service = service;
    }

    @GetMapping("/consultation-services")
    public List<Map<String, Object>> services() {
        return service.services();
    }

    @GetMapping("/admin/consultation-services")
    public List<Map<String, Object>> adminServices() {
        return service.adminServices();
    }

    @PostMapping("/admin/consultation-services")
    public Map<String, Object> create(
        @Valid
        @RequestBody
        ConsultationServiceRequest input) {
        return service.save(null, input);
    }

    @PutMapping("/admin/consultation-services/{id}")
    public Map<String, Object> update(
        @PathVariable
        UUID id,
        @Valid
        @RequestBody
        ConsultationServiceRequest input) {
        return service.save(id, input);
    }

    @PostMapping("/consultation-orders")
    public Map<String, Object> order(
        @Valid
        @RequestBody
        ConsultationOrderRequest input) {
        return service.order(input);
    }

    @GetMapping("/consultation-orders")
    public List<Map<String, Object>> orders() {
        return service.orders();
    }

    @PostMapping("/consultation-orders/{id}/cancel")
    public Map<String, Object> cancel(
        @PathVariable
        UUID id) {
        return service.cancel(id);
    }
}
