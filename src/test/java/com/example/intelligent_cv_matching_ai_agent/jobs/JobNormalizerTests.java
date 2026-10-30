package com.example.intelligent_cv_matching_ai_agent.jobs;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.ImportJobRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.JobSkillRequest;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobNormalizer;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JobNormalizerTests {

    private static final String DESCRIPTION = "Build reliable Java services and support production applications.";

    @Test
    void normalizesExplicitMetadataAndSkillsWithoutChangingOriginalHtml() {
        UUID skillId = UUID.randomUUID();
        String html = "<p>Build reliable Java services and support production applications.</p>";
        var input = request(
            " itviec ",
            "https://itviec.com/it-jobs/backend?utm_source=mail&language=en#details",
            " <b>Backend</b>  Developer ",
            " <strong>Acme</strong>  Engineering ",
            " TP.HCM, VN; Hà Nội, Vietnam ",
            html,
            List.of(new JobSkillRequest(skillId, " nodejs ", true), new JobSkillRequest(null, "React Native", false)),
            " full-time ",
            "<ul><li>Java experience</li><li>SQL experience</li></ul>",
            null,
            null
        );

        var normalized = JobNormalizer.normalize(input);
        assertEquals("ITVIEC", normalized.sourceSite());
        assertEquals("https://itviec.com/it-jobs/backend?language=en", normalized.sourceUrl());
        assertEquals("Backend Developer", normalized.jobTitle());
        assertEquals("Acme Engineering", normalized.companyName());
        assertEquals("Ho Chi Minh; Ha Noi", normalized.location());
        assertEquals("20 - 30 million VND", normalized.salaryText());
        assertEquals(html, normalized.content());
        assertEquals("FULL_TIME", normalized.employmentType());
        assertEquals("Java experience\nSQL experience", normalized.requirements());
        assertEquals(new JobSkillRequest(skillId, "Node.js", true), normalized.skills().getFirst());
        assertEquals(new JobSkillRequest(null, "React Native", false), normalized.skills().get(1));
        assertNull(normalized.responsibilities());
        assertNull(normalized.benefits());
    }

    @Test
    void preservesMultipleLocationsAndUnknownAddressDetails() {
        assertEquals("Ho Chi Minh; Ha Noi; Remote", JobNormalizer.normalizeLocation("Sài Gòn, Hanoi; Telecommute"));
        assertEquals("District 1, Ho Chi Minh", JobNormalizer.normalizeLocation("District 1, HCMC, VN"));
        assertEquals("Singapore; Da Nang", JobNormalizer.normalizeLocation(" Singapore | Đà Nẵng "));
        assertNull(JobNormalizer.normalizeLocation(" <span> </span> "));
        assertEquals("do thi va c++", JobNormalizer.fold(" Đô thị  và C++ "));
    }

    @Test
    void canonicalizesEmploymentLabelsWithoutInferringAbsentMetadata() {
        assertEquals("INTERN", JobNormalizer.employmentType("Thực tập sinh"));
        assertEquals("PART_TIME", JobNormalizer.employmentType("part time"));
        assertEquals("PER_DIEM", JobNormalizer.employmentType("PER_DIEM"));
        assertEquals("OTHER", JobNormalizer.employmentType("flexible assignment"));
        assertNull(JobNormalizer.employmentType(null));
        assertNull(JobNormalizer.employmentType("  "));
        var normalized = JobNormalizer.normalize(
            new ImportJobRequest(
                "ITVIEC",
                "https://itviec.com/it-jobs/backend",
                "Backend Developer",
                "Acme Engineering",
                "HCM",
                null,
                "Backend",
                DESCRIPTION,
                null
            )
        );
        assertTrue(normalized.skills().isEmpty());
        assertNull(normalized.requirements());
        assertNull(normalized.employmentType());
    }

    @Test
    void linesKeepsParagraphsListItemsAndBreaks() {
        assertEquals(
            "Top 3 reasons\nGlobal banks\nHybrid working\nFirst line\nSecond line\nJava & Kotlin",
            JobNormalizer.lines(
                "<h2>Top 3 reasons</h2>\n<ul>\n  <li>Global   banks</li><li>Hybrid working</li></ul>"
                    + "<p>First line<br>Second line</p><p>Java &amp; Kotlin</p>"
            )
        );
        assertEquals(
            "Java experience\nSQL experience",
            JobNormalizer.lines("  Java   experience \n\n SQL experience ")
        );
        assertEquals("", JobNormalizer.lines(null));
    }

    @Test
    void contentHashIgnoresEquivalentMarkupAliasesSkillOrderAndDateOffsets() {
        OffsetDateTime postedAt = OffsetDateTime.parse("2026-10-01T00:00:00Z");
        OffsetDateTime expiry = OffsetDateTime.parse("2026-11-01T00:00:00Z");
        var first = request(
            "ITVIEC",
            "https://itviec.com/it-jobs/backend",
            "Backend Developer",
            "Acme Engineering",
            "HCM",
            "<p>" + DESCRIPTION + "</p>",
            List.of(new JobSkillRequest(null, "js", true), new JobSkillRequest(null, "nodejs", false)),
            "full time",
            "<b>Experience with Java</b>",
            postedAt,
            expiry
        );
        var second = request(
            " itviec ",
            "https://itviec.com/it-jobs/backend?utm_medium=social",
            "Backend  Developer",
            "Acme Engineering",
            "Hồ Chí Minh, Vietnam",
            DESCRIPTION,
            List.of(new JobSkillRequest(null, "Node.js", false), new JobSkillRequest(null, "JavaScript", true)),
            "FULL_TIME",
            "Experience with Java",
            postedAt.withOffsetSameInstant(java.time.ZoneOffset.ofHours(7)),
            expiry.withOffsetSameInstant(java.time.ZoneOffset.ofHours(7))
        );
        assertEquals(JobNormalizer.contentHash(first), JobNormalizer.contentHash(second));
        assertEquals(JobNormalizer.fingerprint(first), JobNormalizer.fingerprint(second));
    }

    @Test
    void fingerprintMatchesAcrossSourcesButRequiresIdenticalSubstantiveDetailsAndCompany() {
        var first = request(
            "ITVIEC",
            "https://itviec.com/it-jobs/backend",
            "Backend Developer",
            "Acme Engineering",
            "HCM",
            DESCRIPTION,
            List.of(),
            "FULL_TIME",
            "Java experience",
            null,
            null
        );
        var otherSource = request(
            "TOPCV",
            "https://topcv.vn/viec-lam/backend",
            "Backend Developer",
            "Acme Engineering",
            "Hồ Chí Minh",
            DESCRIPTION,
            List.of(),
            "FULL_TIME",
            "Java experience",
            OffsetDateTime.parse("2026-10-02T00:00:00Z"),
            OffsetDateTime.parse("2026-11-02T00:00:00Z")
        );
        assertNotEquals(JobNormalizer.contentHash(first), JobNormalizer.contentHash(otherSource));
        assertEquals(JobNormalizer.fingerprint(first), JobNormalizer.fingerprint(otherSource));
        var changedRequirements = request(
            "TOPCV",
            "https://topcv.vn/viec-lam/backend",
            "Backend Developer",
            "Acme Engineering",
            "HCM",
            DESCRIPTION,
            List.of(),
            "FULL_TIME",
            "Python experience",
            null,
            null
        );
        assertNotEquals(JobNormalizer.fingerprint(first), JobNormalizer.fingerprint(changedRequirements));
        var unknownCompany = request(
            "TOPCV",
            "https://topcv.vn/viec-lam/backend",
            "Backend Developer",
            "  ",
            "HCM",
            DESCRIPTION,
            List.of(),
            null,
            null,
            null,
            null
        );
        assertNull(JobNormalizer.fingerprint(unknownCompany));
    }

    @Test
    void changedExpiryAndRequiredSkillRemainPartOfContentHash() {
        var first = request(
            "ITVIEC",
            "https://itviec.com/it-jobs/backend",
            "Backend Developer",
            "Acme Engineering",
            "HCM",
            DESCRIPTION,
            List.of(new JobSkillRequest(null, "Java", true)),
            "FULL_TIME",
            null,
            null,
            OffsetDateTime.parse("2026-11-01T00:00:00Z")
        );
        var changedExpiry = request(
            "ITVIEC",
            "https://itviec.com/it-jobs/backend",
            "Backend Developer",
            "Acme Engineering",
            "HCM",
            DESCRIPTION,
            List.of(new JobSkillRequest(null, "Java", true)),
            "FULL_TIME",
            null,
            null,
            OffsetDateTime.parse("2026-11-02T00:00:00Z")
        );
        var optionalSkill = request(
            "ITVIEC",
            "https://itviec.com/it-jobs/backend",
            "Backend Developer",
            "Acme Engineering",
            "HCM",
            DESCRIPTION,
            List.of(new JobSkillRequest(null, "Java", false)),
            "FULL_TIME",
            null,
            null,
            first.validThrough()
        );
        assertNotEquals(JobNormalizer.contentHash(first), JobNormalizer.contentHash(changedExpiry));
        assertNotEquals(JobNormalizer.contentHash(first), JobNormalizer.contentHash(optionalSkill));
        assertEquals(JobNormalizer.fingerprint(first), JobNormalizer.fingerprint(changedExpiry));
    }

    private ImportJobRequest request(
        String source,
        String url,
        String title,
        String company,
        String location,
        String content,
        List<JobSkillRequest> skills,
        String employmentType,
        String requirements,
        OffsetDateTime postedAt,
        OffsetDateTime validThrough) {
        return new ImportJobRequest(
            source,
            url,
            title,
            company,
            location,
            " 20  -  30 million VND ",
            " Backend ",
            content,
            skills,
            employmentType,
            requirements,
            null,
            null,
            postedAt,
            validThrough
        );
    }
}
