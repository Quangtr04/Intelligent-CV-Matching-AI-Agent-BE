package com.example.intelligent_cv_matching_ai_agent.controller.candidate.jobs;

import com.example.intelligent_cv_matching_ai_agent.dto.response.shared.jobs.MatchingSalaryPolicyResponse;
import com.example.intelligent_cv_matching_ai_agent.service.shared.jobs.MatchingSalaryPolicyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/matching/salary-policy")
public class MatchingSalaryPolicyController {

    private final MatchingSalaryPolicyService service;

    public MatchingSalaryPolicyController(
        MatchingSalaryPolicyService service) {
        this.service = service;
    }

    @GetMapping
    public MatchingSalaryPolicyResponse get() {
        return service.candidatePolicy();
    }
}
