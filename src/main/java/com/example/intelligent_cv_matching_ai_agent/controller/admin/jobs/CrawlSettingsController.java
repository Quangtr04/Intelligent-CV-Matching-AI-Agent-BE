package com.example.intelligent_cv_matching_ai_agent.controller.admin.jobs;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.CrawlSettingsRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.response.admin.jobs.CrawlSettingsResponse;
import com.example.intelligent_cv_matching_ai_agent.service.admin.jobs.CrawlSettingsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/admin", "/api/v1/admin"})
public class CrawlSettingsController {

    private final CrawlSettingsService settings;

    public CrawlSettingsController(
        CrawlSettingsService settings) {
        this.settings = settings;
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

}
