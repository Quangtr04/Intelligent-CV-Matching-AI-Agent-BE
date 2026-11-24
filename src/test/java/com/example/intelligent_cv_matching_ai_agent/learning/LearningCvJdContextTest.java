package com.example.intelligent_cv_matching_ai_agent.learning;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.learning.AssessmentRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.learning.AssessmentRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.learning.AssessmentService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.learning.AssessmentValidator;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.learning.LearningInputContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LearningCvJdContextTest {

    private static final String JD = "Build Java backend services and use SQL transactions safely.";

    private final AssessmentRepository db = mock(AssessmentRepository.class);

    private final CurrentUser current = mock(CurrentUser.class);

    private final AiService ai = mock(AiService.class);

    private final AssessmentService service =
        new AssessmentService(db, current, ai, mock(PlatformTransactionManager.class));

    private final UUID owner = UUID.randomUUID();

    private final UUID job = UUID.randomUUID();

    private final UUID resume = UUID.randomUUID();

    @BeforeEach
    void fixture() {
        when(current.id()).thenReturn(owner);
        when(db.getMarketJobPostsById(job)).thenReturn(fields("job_title", "Java Engineer", "raw_content", JD));
        when(db.listMarketJobSkills(job)).thenReturn(List.of(fields("skill_name", "Java", "is_required", true)));
        when(db.findCandidateProfilesByUserId2(owner)).thenReturn(Optional.of(fields("years_experience", 2)));
        when(db.getResumeForOwner(resume, owner)).thenReturn(confirmedCv("Built Java APIs at Example"));
        when(db.listResumeSkills(resume)).thenReturn(List.of(fields("name", "Java")));
    }

    @Test
    void storedJdAssessmentUsesTheSelectedReviewedCvAndPreservesAnInputSnapshot() {
        var prepared = service.prepareForOwner(owner, new AssessmentRequest(null, job, resume, null));

        var cv = object(prepared.input().get("resume"));
        assertTrue(text(cv.get("text")).contains("Built Java APIs at Example"));
        assertFalse(text(cv.get("text")).contains("obsolete extraction"));
        assertEquals(List.of(fields("name", "Java")), cv.get("declared_skills"));
        assertEquals("CATALOG", prepared.snapshot().get("jd_source"));
        assertEquals(resume.toString(), prepared.snapshot().get("resume_id"));
        assertEquals(prepared.inputHash(), LearningInputContext.hash(prepared.snapshot()));
        assertEquals(
            prepared.inputHash(),
            service.restoreForOwner(owner, prepared.snapshot(), prepared.inputHash()).inputHash()
        );
        verify(ai, never()).generate(anyString(), anyMap());
        verify(ai, never()).generateForUser(any(), anyString(), anyMap());
    }

    @Test
    void pastedJdRemainsPrivateAndUsesRecognizedSkillsWithoutCreatingACatalogJob() {
        var prepared = service.prepareForOwner(owner, new AssessmentRequest("Private Java role", null, resume, JD));

        assertNull(prepared.jobId());
        assertEquals("PASTED", prepared.snapshot().get("jd_source"));
        assertEquals(JD, prepared.input().get("jd_text"));
        assertEquals(
            List.of("Java", "SQL", "Transactions"),
            prepared.skills().stream().map(row -> row.get("skill_name")).toList()
        );
        assertEquals(
            prepared.inputHash(),
            service.restoreForOwner(owner, prepared.snapshot(), prepared.inputHash()).inputHash()
        );
        verify(db, never()).getMarketJobPostsById(any());
        verify(db, never()).insertAssessmentSessions(anyMap());
        verify(ai, never()).generate(anyString(), anyMap());
        verify(ai, never()).generateForUser(any(), anyString(), anyMap());
    }

    @Test
    void contextMustUseExactlyOneJdAndPastedTextAllowsAnOptionalTitleAndCv() {
        for (AssessmentRequest request : List.of(
            new AssessmentRequest(null, null, null, null),
            new AssessmentRequest("Java Engineer", job, resume, JD)
        )) {
            assertFalse(request.isContextValid());
            assertEquals(400, assertThrows(ApiException.class, () -> service.prepareForOwner(owner, request)).status());
        }
        var pasted = new AssessmentRequest(" ", null, resume, JD);
        assertTrue(pasted.isContextValid());
        assertEquals("Pasted JD", service.prepareForOwner(owner, pasted).target());
        assertTrue(new AssessmentRequest("Java Engineer", null, null, JD).isContextValid());
        verify(ai, never()).generate(anyString(), anyMap());
        verify(ai, never()).generateForUser(any(), anyString(), anyMap());
    }

    @Test
    void foreignAndUnconfirmedCvsCannotBeUsedForAssessment() {
        when(db.getResumeForOwner(resume, owner)).thenThrow(ApiException.notFound("Resource not found"));
        assertEquals(
            404,
            assertThrows(
                ApiException.class,
                () -> service.prepareForOwner(owner, new AssessmentRequest(null, job, resume, null))
            ).status()
        );
        doReturn(fields("status", "DRAFT", "raw_content", "Draft Java CV")).when(db).getResumeForOwner(resume, owner);
        assertEquals(
            409,
            assertThrows(
                ApiException.class,
                () -> service.prepareForOwner(owner, new AssessmentRequest(null, job, resume, null))
            ).status()
        );
        verify(ai, never()).generate(anyString(), anyMap());
        verify(ai, never()).generateForUser(any(), anyString(), anyMap());
    }

    @Test
    void cvEditsAndForeignOwnersCannotReuseAQueuedInputSnapshot() {
        var prepared = service.prepareForOwner(owner, new AssessmentRequest(null, job, resume, null));
        when(db.getResumeForOwner(resume, owner)).thenReturn(confirmedCv("Now working on Python data pipelines"));
        assertEquals(
            409,
            assertThrows(
                ApiException.class,
                () -> service.restoreForOwner(owner, prepared.snapshot(), prepared.inputHash())
            ).status()
        );
        assertEquals(
            409,
            assertThrows(
                ApiException.class,
                () -> service.restoreForOwner(UUID.randomUUID(), prepared.snapshot(), prepared.inputHash())
            ).status()
        );
        verify(ai, never()).generate(anyString(), anyMap());
        verify(ai, never()).generateForUser(any(), anyString(), anyMap());
    }

    @Test
    void changesDuringInferenceCannotPublishQuestionsForTheOldCv() {
        when(ai.generate(eq("ASSESSMENT_GENERATE"), anyMap())).thenAnswer(call -> {
            when(db.getResumeForOwner(resume, owner)).thenReturn(confirmedCv("A changed confirmed CV"));
            return fields("questions", questions());
        });

        assertEquals(
            409,
            assertThrows(ApiException.class, () -> service.create(new AssessmentRequest(null, job, resume, null)))
                .status()
        );
        verify(db, never()).insertAssessmentSessions(anyMap());
        verify(db, never()).insertAssessmentQuestions(anyMap());
    }

    @Test
    void cvEvidenceMustBeAnExactQuoteOrAnExplicitEmptyString() {
        var questions = questions();
        AssessmentValidator.validateCandidateEvidence(questions, "Built Java APIs at Example");
        questions.getFirst().put("cv_evidence", "Invented employer experience");
        assertEquals(
            502,
            assertThrows(
                ApiException.class,
                () -> AssessmentValidator.validateCandidateEvidence(questions, "Built Java APIs at Example")
            ).status()
        );
        questions.getFirst().remove("cv_evidence");
        assertThrows(
            ApiException.class,
            () -> AssessmentValidator.validateCandidateEvidence(questions, "Built Java APIs at Example")
        );
    }

    private Map<String, Object> confirmedCv(
        String summary) {
        return fields(
            "status",
            "READY",
            "title",
            "Confirmed CV",
            "language",
            "en",
            "raw_content",
            "obsolete extraction",
            "structured_data",
            fields("summary", summary)
        );
    }

    private List<Map<String, Object>> questions() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            result.add(
                fields(
                    "part",
                    index < 5 ? 1 : 2,
                    "question_text",
                    "Java scenario " + index,
                    "related_skill_name",
                    "Java",
                    "jd_evidence",
                    "Build Java backend services",
                    "cv_evidence",
                    index == 0 ? "Built Java APIs" : "",
                    "options",
                    Map.of("A", "Validate", "B", "Ignore", "C", "Delete", "D", "Skip"),
                    "correct_option",
                    "A",
                    "explanation",
                    "Validation preserves the required invariant."
                )
            );
        }
        return result;
    }
}
