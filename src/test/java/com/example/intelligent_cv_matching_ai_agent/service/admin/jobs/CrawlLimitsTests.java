package com.example.intelligent_cv_matching_ai_agent.service.admin.jobs;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.CrawlRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.CrawlSourceSettingsRequest;
import com.example.intelligent_cv_matching_ai_agent.util.admin.jobs.CrawlSettingsSupport;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CrawlLimitsTests {

    @Test
    void manualAndScheduledRequestsShareDefaultsAndUpperBounds() {
        CrawlRequest request = new CrawlRequest("ITVIEC", "Java", "Backend", null, null);
        assertEquals(50, request.effectiveLimit());
        assertEquals(5, request.effectiveMaxPages());
        var defaults = CrawlSettingsSupport
            .normalize(new CrawlSourceSettingsRequest("ITVIEC", "Java", "Backend", null, null, null));
        assertEquals(50, defaults.limit());
        assertEquals(5, defaults.maxPages());
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertTrue(
                validator.validate(new CrawlRequest("ITVIEC", "Java", "Backend", null, null, 100, 10)).isEmpty()
            );
            assertEquals(
                2,
                validator.validate(new CrawlRequest("ITVIEC", "Java", "Backend", null, null, 101, 11)).size()
            );
            assertTrue(
                validator.validate(new CrawlSourceSettingsRequest("ITVIEC", "Data", "Data", null, 100, 10)).isEmpty()
            );
        }
        assertThrows(
            RuntimeException.class,
            () -> CrawlSettingsSupport
                .normalize(new CrawlSourceSettingsRequest("ITVIEC", "Java", "Backend", null, 101, 10))
        );
    }
}
