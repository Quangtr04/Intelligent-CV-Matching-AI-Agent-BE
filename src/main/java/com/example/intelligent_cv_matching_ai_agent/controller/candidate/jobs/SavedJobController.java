package com.example.intelligent_cv_matching_ai_agent.controller.candidate.jobs;

import com.example.intelligent_cv_matching_ai_agent.service.candidate.jobs.SavedJobService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class SavedJobController {

    private final SavedJobService service;

    public SavedJobController(
        SavedJobService service) {
        this.service = service;
    }

    @GetMapping("/saved-jobs")
    public List<Map<String, Object>> list(
        @RequestParam(defaultValue = "20")
        int limit,
        @RequestParam(defaultValue = "0")
        int offset) {
        return service.list(limit, offset);
    }

    @GetMapping("/saved-jobs/ids")
    public List<Map<String, Object>> ids() {
        return service.ids();
    }

    @PutMapping("/jobs/{id}/saved")
    public Map<String, Object> save(
        @PathVariable
        UUID id) {
        return service.save(id);
    }

    @DeleteMapping("/jobs/{id}/saved")
    public Map<String, Object> remove(
        @PathVariable
        UUID id) {
        return service.remove(id);
    }
}

