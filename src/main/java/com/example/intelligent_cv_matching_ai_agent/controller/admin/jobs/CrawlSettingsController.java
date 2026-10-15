package com.example.intelligent_cv_matching_ai_agent.controller.admin.jobs;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.CrawlSettingsRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.response.admin.jobs.CrawlSettingsResponse;
import com.example.intelligent_cv_matching_ai_agent.service.admin.jobs.CrawlSettingsService;
import com.example.intelligent_cv_matching_ai_agent.service.admin.jobs.JobCrawlService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/admin", "/api/v1/admin"})
public class CrawlSettingsController {

    private final CrawlSettingsService settings;

    private final JobCrawlService crawler;

    public CrawlSettingsController(
        CrawlSettingsService settings,
        JobCrawlService crawler) {
        this.settings = settings;
        this.crawler = crawler;
    }

    @GetMapping("/crawl-settings")
    public CrawlSettingsResponse get() {
        return settings.get();
    }

    @PutMapping("/crawl-settings")
    public CrawlSettingsResponse update(
        @Valid
        @RequestBody
        CrawlSettingsRequest request) {
        return settings.update(request);
    }

    @GetMapping("/crawl-history")
    public List<Map<String, Object>> history(
        @RequestParam(defaultValue = "20")
        int limit,
        @RequestParam(defaultValue = "0")
        int offset) {
        return crawler.runs(limit, offset);
    }
}
