package com.example.intelligent_cv_matching_ai_agent.controller.admin.jobs;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.ImportJobRequest;
import com.example.intelligent_cv_matching_ai_agent.service.shared.jobs.JobService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/api/v1/admin/jobs", "/api/v1/staff/jobs"})
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

    @PutMapping("/{id}")
    public Map<String, Object> updateJob(
        @PathVariable
        UUID id,
        @Valid
        @RequestBody
        ImportJobRequest input) {
        return service.updateJob(id, input);
    }

    @GetMapping("/duplicates")
    public List<Map<String, Object>> duplicates(
        @RequestParam(defaultValue = "20")
        int limit,
        @RequestParam(defaultValue = "0")
        int offset) {
        return service.duplicateJobs(limit, offset);
    }

    @GetMapping("/expired-or-invalid")
    public List<Map<String, Object>> expiredOrInvalid(
        @RequestParam(defaultValue = "20")
        int limit,
        @RequestParam(defaultValue = "0")
        int offset) {
        return service.expiredOrInvalidJobs(limit, offset);
    }

    @PostMapping("/{id}/index")
    public Map<String, Object> index(
        @PathVariable
        UUID id) {
        return service.index(id);
    }

    @PostMapping("/embeddings/backfill")
    public Map<String, Object> backfill(
        @RequestParam(defaultValue = "50")
        int limit) {
        return service.backfillEmbeddings(limit);
    }

    @GetMapping("/embeddings/status")
    public Map<String, Object> embeddingCoverage() {
        return service.embeddingCoverage();
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
