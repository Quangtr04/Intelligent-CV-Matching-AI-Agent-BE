package com.example.intelligent_cv_matching_ai_agent.controller.candidate.resume;

import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeProfileAlignmentService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/resumes/{id}/profile-alignments")
public class ResumeProfileAlignmentController {

    private final ResumeProfileAlignmentService service;

    public ResumeProfileAlignmentController(
        ResumeProfileAlignmentService service) {
        this.service = service;
    }

    @PostMapping
    public Map<String, Object> create(
        @PathVariable
        UUID id) {
        return service.create(id);
    }

    @GetMapping
    public List<Map<String, Object>> list(
        @PathVariable
        UUID id,
        @RequestParam(defaultValue = "20")
        int limit,
        @RequestParam(defaultValue = "0")
        int offset) {
        return service.list(id, limit, offset);
    }

    @GetMapping("/{alignmentId}")
    public Map<String, Object> get(
        @PathVariable
        UUID id,
        @PathVariable
        UUID alignmentId) {
        return service.get(id, alignmentId);
    }

    @GetMapping("/refresh-status")
    public Map<String, Object> refreshStatus(
        @PathVariable
        UUID id) {
        return service.refreshStatus(id);
    }
}
