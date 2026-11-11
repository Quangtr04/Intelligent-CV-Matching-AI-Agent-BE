package com.example.intelligent_cv_matching_ai_agent.controller.admin.management;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.billing.ManagerQuotaLimitsRequest;
import com.example.intelligent_cv_matching_ai_agent.service.admin.management.ManagerQuotaPolicy;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
public class ManagerQuotaController {

    private final ManagerQuotaPolicy policy;

    public ManagerQuotaController(
        ManagerQuotaPolicy policy) {
        this.policy = policy;
    }

    @GetMapping("/api/v1/manager/quota-limits")
    public Map<String, Object> ownLimits() {
        return policy.ownLimits();
    }

    @GetMapping("/api/v1/admin/managers/{id}/quota-limits")
    public Map<String, Object> limits(
        @PathVariable
        UUID id) {
        return policy.managerLimits(id);
    }

    @PutMapping("/api/v1/admin/managers/{id}/quota-limits")
    public Map<String, Object> update(
        @PathVariable
        UUID id,
        @Valid
        @RequestBody
        ManagerQuotaLimitsRequest body) {
        return policy.update(id, body);
    }
}
