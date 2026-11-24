package com.example.intelligent_cv_matching_ai_agent.controller.candidate.learning;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.learning.JobSkillProficiencyRequest;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.shared.jobs.JobSkillProficiencyService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/assessments/skill-proficiency")
public class JobSkillProficiencyController {

    private final CurrentUser user;

    private final JobSkillProficiencyService service;

    public JobSkillProficiencyController(
        CurrentUser user,
        JobSkillProficiencyService service) {
        this.user = user;
        this.service = service;
    }

    @PostMapping
    public Map<String, Object> preview(
        @Valid
        @RequestBody
        JobSkillProficiencyRequest input) {
        user.requireRole("CANDIDATE");
        return service.preview(input.jdText(), user.id());
    }
}
