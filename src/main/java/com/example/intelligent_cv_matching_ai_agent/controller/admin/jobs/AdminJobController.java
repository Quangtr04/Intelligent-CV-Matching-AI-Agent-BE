package com.example.intelligent_cv_matching_ai_agent.controller.admin.jobs;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.ImportJobRequest;
import com.example.intelligent_cv_matching_ai_agent.service.shared.jobs.JobService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/jobs")
public class AdminJobController {

    private final JobService service;

    public AdminJobController(
        JobService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list(
        @RequestParam(defaultValue = "")
        String q,
        @RequestParam(required = false)
        String status,
        @RequestParam(required = false)
        UUID createdBy,
        @RequestParam(required = false)
        String ingestionMethod,
        @RequestParam(defaultValue = "20")
        int limit,
        @RequestParam(defaultValue = "0")
        int offset) {
        return service.managedJobs(q, status, createdBy, ingestionMethod, limit, offset);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(
        @PathVariable
        UUID id) {
        return service.job(id);
    }

    @PostMapping
    public Map<String, Object> importJob(
        @Valid
        @RequestBody
        ImportJobRequest input) {
        return service.importJob(input);
    }

    @PostMapping("/{id}/index")
    public Map<String, Object> index(
        @PathVariable
        UUID id) {
        return service.index(id);
    }

    @PatchMapping("/{id}/status")
    public Map<String, Object> status(
        @PathVariable
        UUID id,
        @RequestBody
        Map<String, Object> input) {
        return service.status(id, input);
    }

    @GetMapping("/{id}/index")
    public Map<String, Object> indexStatus(
        @PathVariable
        UUID id) {
        return service.indexStatus(id);
    }
}
