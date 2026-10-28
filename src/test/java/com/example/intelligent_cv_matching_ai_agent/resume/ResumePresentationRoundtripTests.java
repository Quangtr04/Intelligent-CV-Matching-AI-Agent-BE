package com.example.intelligent_cv_matching_ai_agent.resume;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ResumeData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResumePresentationRoundtripTests {

    @Test
    void normalizationPreservesFrontendFieldsAcrossSaveAndConfirm() {
        Map<String, Object> input = Map.of(
            "full_name",
            "Candidate",
            "position",
            "Frontend Developer",
            "frontend_fields",
            Map.of(
                "website",
                "https://example.com",
                "languages",
                List.of(Map.of("id", "lang-1", "name", "English", "level", "Advanced"))
            ),
            "experience",
            List.of(
                Map.of(
                    "company_name",
                    "Example",
                    "job_title",
                    "Developer",
                    "display_start_date",
                    "2024-06",
                    "highlights",
                    List.of("Reduced page loading time by 20%")
                )
            ),
            "certifications",
            List.of(Map.of("name", "Certificate", "display_date", "2025"))
        );

        Map<String, Object> saved = ResumeData.normalize(input);

        assertEquals("Frontend Developer", saved.get("position"));
        assertEquals(input.get("frontend_fields"), saved.get("frontend_fields"));
        assertEquals(input.get("experience"), saved.get("experience"));
        assertEquals(input.get("certifications"), saved.get("certifications"));
        assertEquals(saved, ResumeData.normalize(saved));
    }

    @Test
    void normalizationPreservesEditorRowIdentityLocationAndGpa() {
        Map<String, Object> input = Map.of(
            "experience",
            List.of(Map.of("id", "experience-1", "location", "Hanoi")),
            "education",
            List.of(Map.of("id", "education-1", "gpa", "3.8/4.0")),
            "projects",
            List.of(Map.of("id", "project-1", "project_name", "Portfolio")),
            "certifications",
            List.of(Map.of("id", "certification-1", "name", "Certificate"))
        );

        Map<String, Object> normalized = ResumeData.normalize(input);

        input.forEach(
            (
                key,
                value) -> assertEquals(value, normalized.get(key))
        );
        assertEquals(normalized, ResumeData.normalize(normalized));
    }

    @Test
    void presentationFieldsDoNotBypassCanonicalDateValidation() {
        assertThrows(
            ApiException.class,
            () -> ResumeData.normalize(
                Map.of("experience", List.of(Map.of("start_date", "2024-06", "display_start_date", "2024-06")))
            )
        );
        assertThrows(ApiException.class, () -> ResumeData.normalize(Map.of("frontend_fields", List.of("bad"))));
        assertThrows(
            ApiException.class,
            () -> ResumeData.normalize(Map.of("experience", List.of(Map.of("highlights", "not an array"))))
        );
    }
}
