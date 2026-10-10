package com.example.intelligent_cv_matching_ai_agent.controller.admin.management;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.ai.AiConfigurationRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.ai.AiPromptRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.billing.PaymentReportResolutionRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.billing.SubscriptionQuotaRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.billing.SubscriptionTierRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.management.AccountCreateRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.management.AccountUpdateRequest;

import com.example.intelligent_cv_matching_ai_agent.service.admin.management.AdminService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final AdminService service;

    public AdminController(
        AdminService service) {
        this.service = service;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return service.dashboard();
    }

    @GetMapping("/users")
    public List<Map<String, Object>> users(
        @RequestParam(defaultValue = "")
        @Size(max = 255)
        String search,
        @RequestParam(defaultValue = "0")
        @Min(0)
        int page,
        @RequestParam(defaultValue = "20")
        @Min(1)
        @Max(100)
        int size) {
        return service.users(search, page, size);
    }

    @GetMapping("/users/{id}")
    public Map<String, Object> account(
        @PathVariable
        UUID id) {
        return service.account(id);
    }

    @PostMapping("/users")
    public Map<String, Object> create(
        @Valid
        @RequestBody
        AccountCreateRequest body) {
        return service.create(body);
    }

    @PutMapping("/users/{id}")
    public Map<String, Object> edit(
        @PathVariable
        UUID id,
        @Valid
        @RequestBody
        AccountUpdateRequest body) {
        return service.edit(id, body);
    }

    @DeleteMapping("/users/{id}")
    public Map<String, Object> delete(
        @PathVariable
        UUID id) {
        return service.delete(id);
    }

    @GetMapping("/subscription-tiers")
    public List<Map<String, Object>> tiers() {
        return service.tiers();
    }

    @PostMapping("/subscription-tiers")
    public Map<String, Object> createTier(
        @Valid
        @RequestBody
        SubscriptionTierRequest body) {
        return service.createTier(body);
    }

    @PutMapping("/subscription-tiers/{id}")
    public Map<String, Object> editTier(
        @PathVariable
        UUID id,
        @Valid
        @RequestBody
        SubscriptionTierRequest body) {
        return service.editTier(id, body);
    }

    @PutMapping("/subscription-tiers/{id}/quotas")
    public List<Map<String, Object>> quotas(
        @PathVariable
        UUID id,
        @Valid
        @RequestBody
        @Size(max = 100)
        List<@Valid SubscriptionQuotaRequest> body) {
        return service.quotas(id, body);
    }

    @GetMapping("/ai/configurations")
    public List<Map<String, Object>> configurations() {
        return service.configurations();
    }

    @PostMapping("/ai/configurations")
    public Map<String, Object> configure(
        @Valid
        @RequestBody
        AiConfigurationRequest body) {
        return service.configure(body);
    }

    @PostMapping("/ai/configurations/{oldVersionId}/restore")
    public Map<String, Object> restoreConfiguration(
        @PathVariable
        UUID oldVersionId) {
        return service.restoreConfiguration(oldVersionId);
    }

    @GetMapping("/ai/prompts")
    public List<Map<String, Object>> prompts() {
        return service.prompts();
    }

    @PostMapping("/ai/prompts")
    public Map<String, Object> prompt(
        @Valid
        @RequestBody
        AiPromptRequest body) {
        return service.prompt(body);
    }

    @PostMapping("/ai/prompts/{oldVersionId}/restore")
    public Map<String, Object> restorePrompt(
        @PathVariable
        UUID oldVersionId) {
        return service.restorePrompt(oldVersionId);
    }

    @GetMapping("/monitoring/{category}")
    public List<Map<String, Object>> monitor(
        @PathVariable
        String category) {
        return service.monitor(category);
    }

    @GetMapping("/payment-reports")
    public List<Map<String, Object>> reports() {
        return service.reports();
    }

    @PostMapping("/payment-reports/{id}/resolve")
    public Map<String, Object> resolve(
        @PathVariable
        UUID id,
        @Valid
        @RequestBody
        PaymentReportResolutionRequest body) {
        return service.resolve(id, body);
    }
}
