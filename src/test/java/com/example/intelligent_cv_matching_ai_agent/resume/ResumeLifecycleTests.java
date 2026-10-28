package com.example.intelligent_cv_matching_ai_agent.resume;

import com.example.intelligent_cv_matching_ai_agent.repository.candidate.resume.ResumeRepository;

import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ResumeFileExtractor;

import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ResumeData;

import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;

import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;

import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResumeLifecycleTests {

    private final SqlStore db = mock(SqlStore.class);

    private final CurrentUser user = mock(CurrentUser.class);

    private final AiService ai = mock(AiService.class);

    private final UUID actor = UUID.randomUUID(), id = UUID.randomUUID(), job = UUID.randomUUID();

    private final ResumeService service = new ResumeService(
        new ResumeRepository(db),
        user,
        ai,
        mock(ResumeFileExtractor.class),
        JsonMapper.builder().build(),
        "./target/test-storage"
    );

    private Map<String, Object> ready() {
        when(user.id()).thenReturn(actor);
        Map<String, Object> resume = new LinkedHashMap<>(
            Map.of(
                "id",
                id,
                "user_id",
                actor,
                "title",
                "Java CV",
                "language",
                "vi",
                "status",
                "READY",
                "content_hash",
                "original",
                "structured_data",
                ResumeData.normalize(Map.of("summary", "Java developer")),
                "raw_content",
                ""
            )
        );
        when(db.one("SELECT * FROM resumes WHERE id=?", id)).thenReturn(resume);
        return resume;
    }

    @Test
    void titleChangesInvalidateExistingVectorsAndDependentRoadmaps() {
        Map<String, Object> resume = ready();
        List<Map<String, Object>> writes = new ArrayList<>();
        when(db.insert(eq("resumes"), anyMap())).thenAnswer(call -> {
            writes.add(new LinkedHashMap<>(call.getArgument(1)));
            return id;
        });
        service.create(
            Map.of("title", "Java CV", "language", "vi", "structured_data", Map.of("summary", "Java developer")),
            false
        );
        resume.put("content_hash", writes.getFirst().get("content_hash"));
        service.update(id, Map.of("title", "Senior Java CV"));
        verify(db).update(
            startsWith("UPDATE resumes SET title="),
            eq("Senior Java CV"),
            eq("vi"),
            eq(""),
            any(),
            argThat(hash -> !resume.get("content_hash").equals(hash)),
            eq("REVIEW_REQUIRED"),
            eq(true),
            eq(id)
        );
        verify(db).update("UPDATE resume_embeddings SET is_current=false WHERE resume_id=?", id);
        verify(db).update(contains("UPDATE roadmaps SET is_outdated=true"), eq(id));
    }

    @Test
    void failedEmbeddingLeavesAFailedJobWithoutPublishingAVector() {
        ready();
        when(ai.embeddingsEnabled()).thenReturn(true);
        when(ai.provider()).thenReturn("openai");
        when(ai.embeddingModel()).thenReturn("embedding-v1");
        when(db.insert(eq("vector_indexing_jobs"), anyMap())).thenReturn(job);
        when(ai.embed(anyString())).thenThrow(new ApiException(502, "Provider failed"));
        assertThrows(ApiException.class, () -> service.embedding(id));
        verify(db).update(
            ("UPDATE vector_indexing_jobs SET status='FAILED',error_message=?," + "completed_at=now() WHERE id=?"),
            "Provider failed",
            job
        );
        verify(db, never()).scalar(anyString(), any(Object[].class));
    }

    @Test
    void concurrentResumeEditPreventsStaleEmbeddingPublication() {
        ready();
        when(ai.embeddingsEnabled()).thenReturn(true);
        when(ai.provider()).thenReturn("openai");
        when(ai.embeddingModel()).thenReturn("embedding-v1");
        when(db.insert(eq("vector_indexing_jobs"), anyMap())).thenReturn(job);
        when(ai.embed(anyString())).thenReturn(new float[1024]);
        when(db.one("SELECT * FROM resumes WHERE id=? FOR UPDATE", id))
            .thenReturn(Map.of("status", "REVIEW_REQUIRED", "content_hash", "newer"));
        assertThrows(ApiException.class, () -> service.embedding(id));
        verify(db).update(startsWith("UPDATE vector_indexing_jobs SET status='FAILED'"), anyString(), eq(job));
        verify(db, never()).update("UPDATE resume_embeddings SET is_current=false WHERE resume_id=?", id);
    }

    @Test
    void reviewedCertificationDataOverridesUnreviewedRawExtraction() {
        String text = service.canonicalText(
            Map.of(
                "title",
                "CV",
                "raw_content",
                "wrong certification",
                "structured_data",
                Map.of("certifications", List.of(Map.of("name", "Verified qualification")))
            )
        );
        assertTrue(text.contains("Verified qualification"));
        assertFalse(text.contains("wrong certification"));
    }

    @Test
    void invalidFiniteNumbersAndOversizedDatabaseFieldsAreRejectedBeforePersistence() {
        assertThrows(
            ApiException.class,
            () -> ResumeData
                .normalize(Map.of("skills", List.of(Map.of("name", "Java", "years_experience", Double.NaN))))
        );
        assertThrows(
            ApiException.class,
            () -> ResumeData
                .normalize(Map.of("skills", List.of(Map.of("name", "Java", "proficiency_level", "x".repeat(51)))))
        );
    }

    @Test
    void confirmationReplacesEachWhitelistedSectionBeforePublishingReadyCv() {
        ready();
        when(db.json(any())).thenReturn(new SqlStore.JsonValue("{}"));
        Map<String, Object> data = Map.of(
            "experience",
            List.of(Map.of("company_name", "Example", "start_date", "2024-01-01", "end_date", "2025-01-01")),
            "education",
            List.of(Map.of("school_name", "University", "start_date", "2020-01-01")),
            "projects",
            List.of(Map.of("project_name", "CV matching", "technologies", List.of("Java"))),
            "certifications",
            List.of(Map.of("name", "Certificate", "issue_date", "2023-01-01"))
        );
        service.confirm(id, Map.of("structured_data", data));
        var order = inOrder(db);
        order.verify(db).update("DELETE FROM resume_experiences WHERE resume_id=?", id);
        order.verify(db).insert(
            eq("resume_experiences"),
            argThat(
                fields -> id.equals(fields.get("resume_id"))
                    && LocalDate.of(2024, 1, 1).equals(fields.get("start_date"))
            )
        );
        order.verify(db).update("DELETE FROM resume_educations WHERE resume_id=?", id);
        order.verify(db).insert(
            eq("resume_educations"),
            argThat(fields -> LocalDate.of(2020, 1, 1).equals(fields.get("start_date")))
        );
        order.verify(db).update("DELETE FROM resume_projects WHERE resume_id=?", id);
        order.verify(db)
            .insert(eq("resume_projects"), argThat(fields -> fields.get("technologies") instanceof SqlStore.JsonValue));
        order.verify(db).update("DELETE FROM resume_certifications WHERE resume_id=?", id);
        order.verify(db).insert(
            eq("resume_certifications"),
            argThat(fields -> LocalDate.of(2023, 1, 1).equals(fields.get("issue_date")))
        );
        order.verify(db).update("DELETE FROM resume_skills WHERE resume_id=?", id);
        order.verify(db)
            .update(startsWith("UPDATE resumes SET structured_data="), any(), eq(""), anyString(), eq(false), eq(id));
    }

    @Test
    void confirmationKeepsEditorPresentationInJsonWithoutInsertingItAsSqlColumnsOrIds() {
        ready();
        JsonMapper mapper = JsonMapper.builder().build();
        when(db.json(any())).thenAnswer(call -> new SqlStore.JsonValue(mapper.writeValueAsString(call.getArgument(0))));
        Map<String, Object> document = Map.of(
            "summary",
            "Reviewed candidate experience",
            "experience",
            List.of(
                Map.of(
                    "id",
                    "experience-1",
                    "company_name",
                    "Example",
                    "job_title",
                    "Developer",
                    "location",
                    "Hanoi",
                    "start_date",
                    "2024-01-01",
                    "display_start_date",
                    "01/2024",
                    "highlights",
                    List.of("Built APIs")
                )
            ),
            "education",
            List.of(
                Map.of(
                    "id",
                    "education-1",
                    "school_name",
                    "University",
                    "gpa",
                    "3.8/4.0",
                    "display_start_date",
                    "2020",
                    "highlights",
                    List.of("Graduated")
                )
            ),
            "projects",
            List.of(Map.of("id", "project-1", "project_name", "Portfolio", "technologies", List.of("Java"))),
            "certifications",
            List.of(
                Map.of(
                    "id",
                    "certification-1",
                    "name",
                    "Certificate",
                    "issue_date",
                    "2025-01-01",
                    "display_date",
                    "2025"
                )
            )
        );

        service.confirm(id, Map.of("structured_data", document));

        Set<String> tables =
            Set.of("resume_experiences", "resume_educations", "resume_projects", "resume_certifications");
        verify(db, times(4)).insert(
            argThat(tables::contains),
            argThat(
                fields -> id.equals(fields.get("resume_id")) && !fields.containsKey("id")
                    && !fields.containsKey("location") && !fields.containsKey("gpa")
                    && !fields.containsKey("highlights")
                    && fields.keySet().stream().noneMatch(key -> key.startsWith("display_"))
            )
        );
        verify(db).insert(
            eq("resume_experiences"),
            argThat(
                fields -> "Example".equals(fields.get("company_name"))
                    && LocalDate.of(2024, 1, 1).equals(fields.get("start_date"))
            )
        );
        verify(db).update(
            startsWith("UPDATE resumes SET structured_data="),
            argThat(
                value -> value instanceof SqlStore.JsonValue json
                    && mapper.readValue(json.content(), Map.class).equals(ResumeData.normalize(document))
            ),
            eq(""),
            anyString(),
            eq(false),
            eq(id)
        );
        verify(ai, never()).embed(anyString());
    }
}
