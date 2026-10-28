package com.example.intelligent_cv_matching_ai_agent.util.shared.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.JobSkillRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class JobPostingParserTests {

    @Test
    void jsonLdGraphExtractsExplicitRequirementsDatesAndConservativeSkills() {
        String html = """
            <script type="application/ld+json">{not-json}</script>
            <script type="application/ld+json">
            {"@graph":[{"@type":"Organization","name":"Unrelated"},{"@type":["Thing","JobPosting"],
             "title":"JavaScript Engineer", "hiringOrganization":{"name":"Example Company"},
             "description":"<div>Build JavaScript applications and maintain scalable production systems.</div>",
             "qualifications":"<h3>Must have</h3><p>JavaScript and SQL required.</p><h3>Nice to have</h3><p>Docker is a plus.</p>",
             "responsibilities":"Maintain browser services.", "jobBenefits":"Training opportunities.",
             "employmentType":["FULL_TIME","PERMANENT"],
             "jobLocation":{"address":{"addressLocality":"TP. HCM","addressCountry":"VN"}},
             "baseSalary":{"currency":"VND","value":{"minValue":20000000,"maxValue":35000000,"unitText":"MONTH"}},
             "datePosted":"2026-10-01", "validThrough":"2026-12-31"}]}
            </script>
            """;
        var parsed = JobPostingParser
            .parse(html, "ITVIEC", "https://itviec.com/it-jobs/javascript-example-1234", "Frontend").orElseThrow();
        assertEquals("json_ld", parsed.quality());
        assertEquals("Ho Chi Minh", parsed.job().location());
        assertEquals("FULL_TIME", parsed.job().employmentType());
        assertEquals("20000000 - 35000000 VND / MONTH", parsed.job().salaryText());
        assertEquals(ZoneOffset.ofHours(7), parsed.job().postedAt().getOffset());
        assertEquals("2027-01-01T00:00+07:00", parsed.job().validThrough().toString());
        assertTrue(parsed.job().requirements().contains("SQL"));
        assertEquals("Maintain browser services.", parsed.job().responsibilities());
        assertEquals("Training opportunities.", parsed.job().benefits());
        Map<String, Boolean> skills = skills(parsed.job().skills());
        assertEquals(true, skills.get("JavaScript"));
        assertEquals(true, skills.get("SQL"));
        assertEquals(false, skills.get("Docker"));
        assertFalse(skills.containsKey("Java"), "JavaScript must not imply Java");
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '~',
        value = {
            "800 - 2,000 USD|MONTH|800 - 2,000 USD / MONTH",
            "800 - 2,000 USD / MONTH|MONTH|800 - 2,000 USD / MONTH",
            "Up to 35tr (Thương lượng)|MONTH|Up to 35tr (Thương lượng) / MONTH",
            "You'll love it|MONTH|You'll love it",
            "Thương lượng|MONTH|Thương lượng",
            "1000|MONTH|1000 USD / MONTH",
            "800 - 2000|MONTH|800 - 2000 USD / MONTH",
            "20 USD|HOUR|20 USD / HOUR",
            "10000 EUR/year|MONTH|10000 EUR/year"}
    )
    void jsonLdSalaryPreservesDisplayCurrencyAndUnknownTextWithoutAppendingAConflictingDefault(
        String input,
        String period,
        String expected) {
        String html = """
            <script type="application/ld+json">
            {"@type":"JobPosting","title":"Backend Developer",
             "description":"Required experience with Java and SQL for production backend services.",
             "baseSalary":{"currency":"USD","value":{"value":"%s","unitText":"%s"}}}
            </script>
            """.formatted(input, period);
        var parsed = JobPostingParser
            .parse(html, "ITVIEC", "https://itviec.com/it-jobs/backend-example-1234", "Backend").orElseThrow();
        assertEquals(expected, parsed.job().salaryText());
    }

    @Test
    void topcvHtmlFallbackExtractsSectionsAndOptionalSkillsWithoutRelatedJobContent() throws Exception {
        var parsed = JobPostingParser.parse(
            fixture("topcv-detail-fallback.html"),
            "TOPCV",
            "https://www.topcv.vn/viec-lam/java-backend/123456.html",
            "Backend"
        ).orElseThrow();
        assertEquals("html", parsed.quality());
        assertEquals("Example Engineering", parsed.job().companyName());
        assertEquals("Ho Chi Minh", parsed.job().location());
        assertEquals("FULL_TIME", parsed.job().employmentType());
        assertEquals("2027-01-01T00:00+07:00", parsed.job().validThrough().toString());
        assertTrue(parsed.job().benefits().contains("Được đào tạo"));
        Map<String, Boolean> skills = skills(parsed.job().skills());
        assertEquals(true, skills.get("Java"));
        assertEquals(true, skills.get("SQL"));
        assertEquals(true, skills.get("Spring Boot"));
        assertEquals(false, skills.get("Docker"));
        assertEquals(false, skills.get("Kubernetes"));
        assertFalse(skills.containsKey("Python"), "Related job cards cannot supply the current job skills");
    }

    @Test
    void itviecFallbackReadsEnglishDetailHeadings() throws Exception {
        var parsed = JobPostingParser.parse(
            fixture("itviec-detail-fallback.html"),
            "ITVIEC",
            "https://itviec.com/it-jobs/platform-example-5678",
            "Frontend"
        ).orElseThrow();
        assertEquals("html", parsed.quality());
        assertEquals("Ha Noi", parsed.job().location());
        assertEquals("CONTRACT", parsed.job().employmentType());
        Map<String, Boolean> skills = skills(parsed.job().skills());
        assertEquals(true, skills.get("JavaScript"));
        assertEquals(true, skills.get("Node.js"));
        assertEquals(false, skills.get("Docker"));
        assertFalse(skills.containsKey("Java"));
    }

    @Test
    void multipleJsonLdPostingsStayRejectedEvenWhenAValidHtmlDetailExists() throws Exception {
        String json = """
            <script type="application/ld+json">[
              {"@type":"JobPosting","title":"One","description":"Description one long enough for a real job posting."},
              {"@type":"JobPosting","title":"Two","description":"Description two long enough for a real job posting."}
            ]</script>
            """;
        assertThrows(
            ApiException.class,
            () -> JobPostingParser.parse(
                json + fixture("topcv-detail-fallback.html"),
                "TOPCV",
                "https://www.topcv.vn/viec-lam/java-backend/123456.html",
                "Backend"
            )
        );
    }

    @Test
    void invalidJsonLdDatesDoNotFallBackToHtmlOrInventADeadline() {
        String html = """
            <script type="application/ld+json">
            {"@type":"JobPosting","title":"Backend Developer",
            "description":"Maintain reliable services and production backend applications.",
            "validThrough":"2026-02-30"}
            </script>
            """;
        assertThrows(
            ApiException.class,
            () -> JobPostingParser.parse(html, "ITVIEC", "https://itviec.com/it-jobs/backend-example-1234", "Backend")
        );
    }

    @Test
    void listingAndChallengePagesCannotPassHtmlFallback() throws Exception {
        assertTrue(
            JobPostingParser
                .parse(fixture("itviec-detail-fallback.html"), "ITVIEC", "https://itviec.com/it-jobs/java", "Backend")
                .isEmpty()
        );
        assertTrue(
            JobPostingParser.parse(
                "<main><h1>Verify you are human</h1><p>Access denied</p></main>",
                "ITVIEC",
                "https://itviec.com/it-jobs/backend-example-1234",
                "Backend"
            ).isEmpty()
        );
    }

    @Test
    void textOnlyDescriptionCanSupplyExplicitRequiredSkillsWithoutInventingEmploymentOrExpiry() {
        String html = """
            <script type="application/ld+json">
            {"@type":"JobPosting","title":"Backend Engineer",
            "description":"Required experience with Java and SQL for production backend services."}
            </script>
            """;
        var job = JobPostingParser.parse(html, "ITVIEC", "https://itviec.com/it-jobs/backend-example-1234", "Backend")
            .orElseThrow().job();
        assertEquals(true, skills(job.skills()).get("Java"));
        assertEquals(true, skills(job.skills()).get("SQL"));
        assertNull(job.employmentType());
        assertNull(job.validThrough());
        assertNull(job.companyName());
    }

    private Map<String, Boolean> skills(
        java.util.List<JobSkillRequest> values) {
        return values.stream().collect(Collectors.toMap(JobSkillRequest::name, JobSkillRequest::required));
    }

    private String fixture(
        String name)
        throws IOException {
        try (var input = getClass().getResourceAsStream("/jobs/" + name)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
