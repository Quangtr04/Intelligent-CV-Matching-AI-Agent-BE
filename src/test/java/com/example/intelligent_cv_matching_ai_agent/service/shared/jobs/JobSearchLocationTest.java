package com.example.intelligent_cv_matching_ai_agent.service.shared.jobs;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.jobs.JobRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JobSearchLocationTest {

    private final JobRepository db = mock(JobRepository.class);

    private final JobService service = new JobService(db, mock(CurrentUser.class), mock(AiService.class));

    @ParameterizedTest
    @CsvSource(
        value = {
            "Hà Nội|%ha noi%",
            "Hanoi|%ha noi%",
            "Đà Nẵng|%da nang%",
            "DaNang|%da nang%",
            "TP. HCM|%ho chi minh%",
            "Telecommute|%remote%",
            "Cần Thơ|%can tho%",
            "Đà Lạt|%da lat%"},
        delimiter = '|'
    )
    void aliasesAndVietnameseLocationsUseCanonicalAccentFreeSearchPatterns(
        String location,
        String pattern) {
        List<Map<String, Object>> jobs = List.of(Map.of("location", location));
        when(db.listMarketJobPostsByStatus("%Java%", pattern, 25, 3)).thenReturn(jobs);

        assertEquals(jobs, service.jobs("Java", location, 25, 3));

        verify(db).listMarketJobPostsByStatus("%Java%", pattern, 25, 3);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "Toàn quốc", "All Cities"})
    void absentLocationSearchesAllRegions(
        String location) {
        service.jobs("Java", location, 10, 0);

        verify(db).listMarketJobPostsByStatus("%Java%", "%%", 10, 0);
    }

    @Test
    void exposesMonthlySalaryBoundsInVndForCatalogFilters() {
        var row = Map.<String, Object>of("salary_text", "35 - 50 triệu");
        when(db.listMarketJobPostsByStatus("%%", "%%", 20, 0)).thenReturn(List.of(row));

        var result = service.jobs("", "", 20, 0).getFirst();
        assertEquals(0, new BigDecimal("35000000").compareTo((BigDecimal) result.get("monthly_salary_min_vnd")));
        assertEquals(0, new BigDecimal("50000000").compareTo((BigDecimal) result.get("monthly_salary_max_vnd")));
        assertFalse(row.containsKey("monthly_salary_min_vnd"));
    }

    @Test
    void locationWildcardsAreLiteralWhileExistingKeywordSearchSemanticsRemain() {
        service.jobs("Java_%", "Cần Thơ_100%!", 10, 0);

        verify(db).listMarketJobPostsByStatus("%Java_%%", "%can tho!_100!%!!%", 10, 0);
    }
}
