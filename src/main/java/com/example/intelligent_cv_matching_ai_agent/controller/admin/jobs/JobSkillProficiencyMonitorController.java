package com.example.intelligent_cv_matching_ai_agent.controller.admin.jobs;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.jobs.JobSkillProficiencyRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

@RestController
@RequestMapping({"/api/v1/admin/jobs/skill-proficiency", "/api/v1/staff/jobs/skill-proficiency"})
public class JobSkillProficiencyMonitorController {

    private static final Set<String> STATES = Set.of("PENDING", "PROCESSING", "COMPLETED", "RETRY", "FAILED");

    private final CurrentUser user;

    private final JobSkillProficiencyRepository db;

    public JobSkillProficiencyMonitorController(
        CurrentUser user,
        JobSkillProficiencyRepository db) {
        this.user = user;
        this.db = db;
    }

    @GetMapping
    public Map<String, Object> list(
        @RequestParam(defaultValue = "PENDING")
        String status,
        @RequestParam(defaultValue = "20")
        int limit,
        @RequestParam(defaultValue = "0")
        int offset) {
        user.requireStaffOrOperationsRole();
        if (!STATES.contains(status))
            throw ApiException.badRequest("Invalid Skill Rubric status");
        return fields("counts", db.statusCounts(), "jobs", db.statusJobs(status, pageSize(limit), offset(offset)));
    }

    @PostMapping("/retry-failed")
    public Map<String, Object> retryFailed() {
        user.requireStaffOrOperationsRole();
        return fields("queued", db.retryFailed());
    }

    @PostMapping("/{jobId}/retry")
    public Map<String, Object> retry(
        @PathVariable
        UUID jobId) {
        user.requireStaffOrOperationsRole();
        return fields("queued", db.retry(jobId));
    }
}
