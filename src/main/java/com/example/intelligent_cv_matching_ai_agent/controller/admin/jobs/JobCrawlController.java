package com.example.intelligent_cv_matching_ai_agent.controller.admin.jobs;

import com.example.intelligent_cv_matching_ai_agent.service.admin.jobs.JobCrawlService;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.CrawlRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/jobs/crawl")
public class JobCrawlController {

    private final JobCrawlService crawler;

    public JobCrawlController(
        JobCrawlService crawler) {
        this.crawler = crawler;
    }

    @PostMapping
    public Map<String, Object> crawl(
        @Valid
        @RequestBody
        CrawlRequest input) {
        return crawler.crawl(input);
    }

    @GetMapping
    public List<Map<String, Object>> runs(
        @RequestParam(defaultValue = "20")
        int limit,
        @RequestParam(defaultValue = "0")
        int offset) {
        return crawler.runs(limit, offset);
    }

    @GetMapping("/{id}")
    public Map<String, Object> run(
        @PathVariable
        UUID id) {
        return crawler.run(id);
    }
}
