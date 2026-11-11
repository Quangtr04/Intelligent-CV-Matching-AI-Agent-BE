package com.example.intelligent_cv_matching_ai_agent.resume;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.resume.ResumePdfHtml;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ResumePdfHtmlTests {

    private static final String TEMPLATE = """
        <!DOCTYPE html><html><head><meta charset="UTF-8"><style>
        body { display: grid; grid-template-columns: 1fr 2fr; color: #14532d; }
        </style></head><body><header><h1 data-cv-field="fullName">Sample Candidate</h1>
        <p data-cv-field="headline">Sample Headline</p><p data-cv-field="contact">Sample Contact</p>
        <div class="cv-avatar">SS</div></header>
        <section data-cv-section="summary"><h2>Selected Profile</h2><p>Sample Summary</p></section>
        <section data-cv-section="experience"><h2>Selected Experience</h2><p>Sample Employer</p></section>
        <section class="cv-section"><h2>Selected Projects</h2>
        <div data-cv-section="projects">Sample Project</div></section>
        <section data-cv-section="education"><h2>Selected Education</h2>Sample Education</section>
        <section data-cv-section="skills"><h2>Selected Skills</h2>Sample Skills</section>
        <section data-cv-section="certifications"><h2>Selected Certificates</h2>Sample Certificate</section>
        </body></html>
        """;

    @Test
    void builderSoftSkillsLanguagesAndAwardsAreEscapedAndPreservedInThePdf() {
        Map<String, Object> data = Map.of(
            "frontend_fields",
            Map.of(
                "softSkills",
                List.of("Teamwork <script>alert(1)</script>"),
                "languages",
                List.of(Map.of("name", "English", "level", "B2")),
                "awards",
                List.of(
                    Map.of(
                        "title",
                        "Project award",
                        "issuer",
                        "University",
                        "year",
                        "2025",
                        "description",
                        "Built the winning project"
                    )
                )
            )
        );

        String html = ResumePdfHtml.render(resume(data), TEMPLATE);
        Document document = Jsoup.parse(html);

        assertTrue(document.selectFirst("[data-cv-section=skills]").text().contains("Teamwork"));
        assertTrue(document.selectFirst("[data-cv-section=skills]").text().contains("English (B2)"));
        assertTrue(document.selectFirst("[data-cv-section=certifications]").text().contains("Project award"));
        assertTrue(document.selectFirst("[data-cv-section=certifications]").text().contains("2025"));
        assertTrue(
            document.selectFirst("[data-cv-section=certifications]").text().contains("Built the winning project")
        );
        assertTrue(document.select("script").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"softSkills", "languages", "awards"})
    void changingBuilderFactsChangesTheStoredPdfFingerprint(
        String field) {
        Map<String, Object> value = Map.of(
            "softSkills",
            List.of("Teamwork"),
            "languages",
            List.of(Map.of("name", "English", "level", "B2")),
            "awards",
            List.of(Map.of("title", "Project award", "year", "2025"))
        );
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        String before = ResumePdfHtml.inputHash(mapper, resume(Map.of()), TEMPLATE);
        String after = ResumePdfHtml
            .inputHash(mapper, resume(Map.of("frontend_fields", Map.of(field, value.get(field)))), TEMPLATE);

        assertNotEquals(before, after);
    }

    @Test
    void selectedTemplatePreservesItsStyleAndHeadingsWhileReplacingEverySampleSlot() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("full_name", "Nguyễn An");
        data.put("position", "Java Developer");
        data.put("email", "an@example.test");
        data.put("summary", "Reviewed candidate summary");
        data.put("frontend_fields", Map.of("github", "github.com/an"));
        data.put(
            "experience",
            List.of(
                Map.of(
                    "job_title",
                    "Backend Engineer",
                    "company_name",
                    "Actual Employer",
                    "start_date",
                    "2024-01",
                    "is_current",
                    true,
                    "description",
                    "Built verified APIs",
                    "highlights",
                    List.of("Delivered a Java service")
                )
            )
        );
        data.put(
            "projects",
            List.of(
                Map.of("project_name", "Actual Project", "role", "Developer", "technologies", List.of("Java", "Spring"))
            )
        );
        data.put("education", List.of(Map.of("school_name", "Actual University", "degree", "Bachelor")));
        data.put("skills", List.of(Map.of("name", "Java")));
        data.put("certifications", List.of(Map.of("name", "Actual Certificate", "issuer", "Actual Issuer")));

        String html = ResumePdfHtml.render(resume(data), TEMPLATE);
        Document document = Jsoup.parse(html);

        assertTrue(html.contains("grid-template-columns: 1fr 2fr"));
        assertEquals("Nguyễn An", document.selectFirst("[data-cv-field=fullName]").text());
        assertEquals("Java Developer", document.selectFirst("[data-cv-field=headline]").text());
        assertTrue(document.selectFirst("[data-cv-field=contact]").text().contains("github.com/an"));
        assertEquals("NA", document.selectFirst(".cv-avatar").text());
        assertEquals("Selected Experience", document.selectFirst("[data-cv-section=experience] h2").text());
        assertTrue(document.selectFirst("[data-cv-section=experience]").text().contains("Actual Employer"));
        assertTrue(document.selectFirst("[data-cv-section=experience]").text().contains("Present"));
        assertTrue(document.selectFirst("[data-cv-section=projects]").text().contains("Java, Spring"));
        assertFalse(document.body().text().contains("Sample"));
        assertTrue(document.body().text().contains("Actual Certificate"));
        assertEquals(6, document.select("[data-cv-section]").size());
    }

    @Test
    void reviewedValuesAreEscapedAsTextInHeadersSectionsEntriesAndLists() {
        String attack = "<script>alert('CV')</script><img src=x onerror=alert(1)> & private";
        Map<String, Object> data = Map.of(
            "full_name",
            attack,
            "position",
            attack,
            "email",
            attack,
            "summary",
            attack,
            "experience",
            List.of(
                Map.of(
                    "job_title",
                    attack,
                    "company_name",
                    attack,
                    "description",
                    attack,
                    "highlights",
                    List.of(attack)
                )
            ),
            "projects",
            List.of(Map.of("project_name", attack, "technologies", List.of(attack)))
        );

        String html = ResumePdfHtml.render(resume(data), TEMPLATE);
        Document document = Jsoup.parse(html);

        assertTrue(document.select("script, iframe, object, embed, img, a, link").isEmpty());
        assertEquals(attack, document.selectFirst("[data-cv-field=fullName]").text());
        assertEquals(attack, document.selectFirst("[data-cv-section=summary] p").text());
        assertEquals(attack, document.selectFirst("[data-cv-section=experience] li").text());
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("&amp; private"));
        assertTrue(
            document.selectFirst("meta[http-equiv=Content-Security-Policy]").attr("content")
                .contains("default-src 'none'")
        );
    }

    @Test
    void emptySectionsRemoveTheirSampleTextAndHeadingWithoutDeletingPopulatedNeighbors() {
        Document document = Jsoup.parse(
            ResumePdfHtml
                .render(resume(Map.of("full_name", "Candidate", "summary", "Actual reviewed summary")), TEMPLATE)
        );

        assertEquals(1, document.select("[data-cv-section]").size());
        assertTrue(document.body().text().contains("Selected Profile"));
        assertTrue(document.body().text().contains("Actual reviewed summary"));
        assertFalse(document.body().text().contains("Selected Projects"));
        assertFalse(document.body().text().contains("Selected Experience"));
        assertFalse(document.body().text().contains("Sample"));
        assertTrue(document.select("[data-cv-field=headline], [data-cv-field=contact]").isEmpty());
    }

    @Test
    void defaultTemplateUsesRawTextOnlyWhenReviewedContentHasNoSections() {
        Map<String, Object> resume = resume(Map.of("full_name", "Raw Candidate"));
        resume.put("raw_content", "Original extracted CV\nBackend <developer> & Java");

        Document document = Jsoup.parse(ResumePdfHtml.render(resume, ""));

        assertEquals("Raw Candidate", document.selectFirst("[data-cv-field=fullName]").text());
        assertTrue(document.selectFirst("[data-cv-section=summary]").text().contains("Original extracted CV"));
        assertTrue(document.select("developer").isEmpty());
        assertTrue(document.selectFirst("[data-cv-section=summary]").text().contains("<developer> & Java"));
    }

    @Test
    void reviewedEntriesNeverAcquireStaleRawSummaryContent() {
        Map<String, Object> resume =
            resume(Map.of("full_name", "Candidate", "skills", List.of(Map.of("name", "Java"))));
        resume.put("raw_content", "Stale raw text must not be printed");

        Document document = Jsoup.parse(ResumePdfHtml.render(resume, null));

        assertTrue(document.select("[data-cv-section=summary]").isEmpty());
        assertFalse(document.body().text().contains("Stale raw text"));
        assertEquals("Java", document.selectFirst("[data-cv-section=skills]").text());
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "<script>alert(1)</script>",
            "<iframe src='https://example.test'></iframe>",
            "<img src='https://example.test/private.png'>",
            "<style>@import 'https://example.test/private.css';</style>",
            "<div onclick='alert(1)'>Injected action</div>"}
    )
    void unsafeAdministratorTemplateIsRejectedBeforePrinting(
        String injection) {
        String unsafe = TEMPLATE.replace("</body>", injection + "</body>");

        assertEquals(
            400,
            assertThrows(
                ApiException.class,
                () -> ResumePdfHtml.render(resume(Map.of("full_name", "Candidate")), unsafe)
            ).status()
        );
    }

    private static Map<String, Object> resume(
        Map<String, Object> data) {
        return new LinkedHashMap<>(Map.of("title", "CV", "language", "en", "structured_data", data));
    }
}
