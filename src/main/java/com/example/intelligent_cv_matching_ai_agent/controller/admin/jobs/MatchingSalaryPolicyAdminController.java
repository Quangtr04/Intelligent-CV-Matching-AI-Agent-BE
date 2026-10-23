package com.example.intelligent_cv_matching_ai_agent.controller.admin.jobs;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.MatchingSalaryPolicyRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.response.shared.jobs.MatchingSalaryPolicyResponse;
import com.example.intelligent_cv_matching_ai_agent.service.shared.jobs.MatchingSalaryPolicyService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/matching/salary-policy")
public class MatchingSalaryPolicyAdminController {

    private final MatchingSalaryPolicyService service;

    public MatchingSalaryPolicyAdminController(
        MatchingSalaryPolicyService service) {
        this.service = service;
    }

    @GetMapping
    public MatchingSalaryPolicyResponse get() {
        return service.administratorPolicy();
    }

    @PutMapping
    public MatchingSalaryPolicyResponse update(
        @Valid
        @RequestBody
        MatchingSalaryPolicyRequest request) {
        return service.update(request);
    }
}
