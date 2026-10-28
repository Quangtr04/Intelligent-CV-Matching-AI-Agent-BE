package com.example.intelligent_cv_matching_ai_agent.controller.candidate.learning;

import com.example.intelligent_cv_matching_ai_agent.service.candidate.learning.AssessmentPreparationService;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/assessments")
public class AssessmentPreparationController {

    private final AssessmentPreparationService service;

    public AssessmentPreparationController(
        AssessmentPreparationService service) {
        this.service = service;
    }

    @GetMapping("/preparation")
    public Map<String, Object> preparation(
        @RequestParam
        UUID jobId) {
        return service.prepare(jobId);
    }
}
