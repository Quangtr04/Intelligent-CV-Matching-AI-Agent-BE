package com.example.intelligent_cv_matching_ai_agent.controller.shared.jobs;

import com.example.intelligent_cv_matching_ai_agent.service.shared.jobs.JobService;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class JobController {

    private final JobService service;

    public JobController(
        JobService service) {
        this.service = service;
    }

    @GetMapping("/skills")
    public List<Map<String, Object>> skills() {
        return service.skills();
    }

    @GetMapping("/jobs")
    public List<Map<String, Object>> jobs(
        @RequestParam(defaultValue = "")
        String q,
        @RequestParam(defaultValue = "")
        String location,
        @RequestParam(defaultValue = "20")
        int limit,
        @RequestParam(defaultValue = "0")
        int offset) {
        return service.jobs(q, location, limit, offset);
    }

    @GetMapping("/jobs/{id}")
    public Map<String, Object> job(
        @PathVariable
        UUID id) {
        return service.job(id);
    }

}
