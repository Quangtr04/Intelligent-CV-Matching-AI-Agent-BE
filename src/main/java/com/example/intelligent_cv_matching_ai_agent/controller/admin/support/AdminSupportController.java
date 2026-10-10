package com.example.intelligent_cv_matching_ai_agent.controller.admin.support;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.support.SupportTicketUpdateRequest;
import com.example.intelligent_cv_matching_ai_agent.service.shared.support.SupportService;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/support/tickets")
public class AdminSupportController {

    private final SupportService service;

    public AdminSupportController(
        SupportService service) {
        this.service = service;
    }

    @PatchMapping("/{id}")
    public Map<String, Object> update(
        @PathVariable
        UUID id,
        @Valid
        @RequestBody
        SupportTicketUpdateRequest body) {
        return service.update(id, body);
    }
}
