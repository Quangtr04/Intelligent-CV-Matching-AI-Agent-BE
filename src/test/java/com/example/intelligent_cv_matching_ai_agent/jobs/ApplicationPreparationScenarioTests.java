package com.example.intelligent_cv_matching_ai_agent.jobs;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.jobs.ApplicationDraftUpdateRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.jobs.CvRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.jobs.DraftRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.jobs.CareerToolsRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.jobs.CareerToolsService;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.jobs.MatchingService;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Tests the existing application-draft service; the new manual editor API is a separate open gap. */
class ApplicationPreparationScenarioTests {

    private final CareerToolsRepository repository = mock(CareerToolsRepository.class);

    private final ResumeService resumes = mock(ResumeService.class);

    private final AiService ai = mock(AiService.class);

    private final UUID candidate = UUID.randomUUID();

    private final UUID resumeId = UUID.randomUUID();

    private final UUID jobId = UUID.randomUUID();

    private final UUID draftId = UUID.randomUUID();

    private final Map<String, Object> cv = new LinkedHashMap<>();

    private final Map<String, Object> job = new LinkedHashMap<>();

    private CareerToolsService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                candidate.toString(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_CANDIDATE"))
            )
        );
        ObjectProvider<StringRedisTemplate> redis = mock(ObjectProvider.class);
        service =
            new CareerToolsService(repository, new CurrentUser(), mock(MatchingService.class), resumes, ai, redis);
        cv.putAll(Map.of("id", resumeId, "status", "READY", "structured_data", Map.of("summary", "Built Java APIs")));
        job.putAll(
            Map.of(
                "id",
                jobId,
                "job_title",
                "Java developer",
                "source_url",
                "https://itviec.com/it-jobs/test-java-role"
            )
        );
        when(resumes.owned(resumeId)).thenReturn(cv);
        when(resumes.canonicalText(cv)).thenReturn("Reviewed Java API experience");
        when(repository.getMarketJobPostsById(jobId)).thenReturn(job);
        when(ai.model()).thenReturn("test-model");
        when(ai.generate(eq("APPLICATION_EMAIL"), anyMap())).thenReturn(validGeneration());
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void legacyGenerationLinksBothDocumentsToTheOwnedReadyCvAndJob() {
        UUID emailId = UUID.randomUUID();
        UUID letterId = UUID.randomUUID();
        when(repository.insertApplicationDrafts(anyMap())).thenReturn(emailId, letterId);
        List<Map<String, Object>> saved = List.of(Map.of("id", emailId), Map.of("id", letterId));
        when(repository.listApplicationDraftsById(emailId, letterId)).thenReturn(saved);

        assertSame(saved, service.createDraft(jobId, new DraftRequest(resumeId, "en")));

        verify(ai).generate(
            eq("APPLICATION_EMAIL"),
            argThat(
                input -> input.get("resume").equals(cv.get("structured_data"))
                    && "Reviewed Java API experience".equals(input.get("resume_text"))
                    && "en".equals(input.get("language")) && input.get("job").equals(job)
            )
        );
        verify(repository).insertApplicationDrafts(
            argThat(
                fields -> candidate.equals(fields.get("user_id")) && resumeId.equals(fields.get("resume_id"))
                    && jobId.equals(fields.get("market_job_id")) && "APPLICATION_EMAIL".equals(fields.get("draft_type"))
                    && "Java application\n\nMy factual application email".equals(fields.get("content"))
                    && !fields.containsKey("status")
            )
        );
        verify(repository).insertApplicationDrafts(
            argThat(
                fields -> candidate.equals(fields.get("user_id")) && resumeId.equals(fields.get("resume_id"))
                    && jobId.equals(fields.get("market_job_id")) && "COVER_LETTER".equals(fields.get("draft_type"))
                    && "My factual cover letter".equals(fields.get("content")) && !fields.containsKey("status")
            )
        );
    }

    @ParameterizedTest
    @ValueSource(
        strings = {"missing-email", "missing-letter", "blank-email", "blank-letter", "long-email", "long-letter"}
    )
    void invalidGeneratedDocumentCannotPersistEitherHalfOfThePacket(
        String invalid) {
        Map<String, Object> generated = new LinkedHashMap<>(validGeneration());
        String key = invalid.endsWith("email") ? "email" : "cover_letter";
        if (invalid.startsWith("missing")) {
            generated.remove(key);
        } else {
            generated.put(key, invalid.startsWith("blank") ? "  " : "x".repeat(30001));
        }
        when(ai.generate(eq("APPLICATION_EMAIL"), anyMap())).thenReturn(generated);

        assertEquals(
            502,
            assertThrows(ApiException.class, () -> service.createDraft(jobId, new DraftRequest(resumeId, "en")))
                .status()
        );

        verify(repository, never()).insertApplicationDrafts(anyMap());
        verify(repository, never()).listApplicationDraftsById(any(), any());
    }

    @Test
    void providerFailureCannotCreateAnEmptyApplicationPacket() {
        when(ai.generate(eq("APPLICATION_EMAIL"), anyMap())).thenThrow(new ApiException(502, "Provider unavailable"));

        assertEquals(
            502,
            assertThrows(ApiException.class, () -> service.createDraft(jobId, new DraftRequest(resumeId, "en")))
                .status()
        );

        verify(repository, never()).insertApplicationDrafts(anyMap());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "REVIEW_REQUIRED", "ARCHIVED"})
    void unconfirmedOrArchivedCvCannotSpendAiOnAnApplication(
        String status) {
        cv.put("status", status);

        ApiException failure =
            assertThrows(ApiException.class, () -> service.createDraft(jobId, new DraftRequest(resumeId, "en")));

        assertEquals("ARCHIVED".equals(status) ? 404 : 409, failure.status());
        verifyNoInteractions(ai);
        verify(repository, never()).insertApplicationDrafts(anyMap());
    }

    @Test
    void foreignCvCannotGenerateAPacketOrLogAnExternalRedirect() {
        when(resumes.owned(resumeId)).thenThrow(ApiException.notFound("Resource not found"));

        assertEquals(
            404,
            assertThrows(ApiException.class, () -> service.createDraft(jobId, new DraftRequest(resumeId, "en")))
                .status()
        );
        assertEquals(
            404,
            assertThrows(ApiException.class, () -> service.apply(jobId, new CvRequest(resumeId))).status()
        );

        verifyNoInteractions(ai);
        verify(repository, never()).insertApplicationDrafts(anyMap());
        verify(repository, never()).insertExternalApplyEvents(anyMap());
    }

    @Test
    void inactiveOrExpiredJobCannotGenerateOrRedirect() {
        when(repository.getMarketJobPostsById(jobId)).thenThrow(ApiException.notFound("Resource not found"));

        assertEquals(
            404,
            assertThrows(ApiException.class, () -> service.createDraft(jobId, new DraftRequest(resumeId, "en")))
                .status()
        );
        assertEquals(404, assertThrows(ApiException.class, () -> service.apply(jobId, null)).status());

        verifyNoInteractions(ai);
        verify(repository, never()).insertApplicationDrafts(anyMap());
        verify(repository, never()).insertExternalApplyEvents(anyMap());
    }

    @Test
    void revisingAnOwnedDraftChecksOwnershipBeforeWritingAndReturnsTheSavedRow() {
        Map<String, Object> saved = Map.of("id", draftId, "user_id", candidate, "content", "Revised factual letter");
        when(repository.getApplicationDraftsById2(draftId)).thenReturn(saved);

        assertSame(saved, service.editDraft(draftId, new ApplicationDraftUpdateRequest("Revised factual letter")));

        var order = inOrder(repository);
        order.verify(repository).getApplicationDraftsById(draftId, candidate);
        order.verify(repository).updateApplicationDraftsById("Revised factual letter", draftId);
        order.verify(repository).getApplicationDraftsById2(draftId);
        verifyNoInteractions(ai);
    }

    @Test
    void foreignDraftCannotBeRevisedOrReadBack() {
        when(repository.getApplicationDraftsById(draftId, candidate))
            .thenThrow(ApiException.notFound("Resource not found"));

        assertEquals(
            404,
            assertThrows(
                ApiException.class,
                () -> service.editDraft(draftId, new ApplicationDraftUpdateRequest("Unauthorized revision"))
            ).status()
        );

        verify(repository, never()).updateApplicationDraftsById(any(), any());
        verify(repository, never()).getApplicationDraftsById2(any());
        verifyNoInteractions(ai);
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "http://itviec.com/it-jobs/test",
            "https://evil.example/test",
            "https://itviec.com.evil.example/test",
            "https://user@itviec.com/it-jobs/test",
            "https://itviec.com:443/it-jobs/test",
            "javascript:alert(1)"}
    )
    void invalidSourceCannotProduceAnApplicationRedirectEvent(
        String target) {
        job.put("source_url", target);

        assertEquals(
            400,
            assertThrows(ApiException.class, () -> service.apply(jobId, new CvRequest(resumeId))).status()
        );

        verify(repository, never()).insertExternalApplyEvents(anyMap());
        verifyNoInteractions(ai);
    }

    @Test
    void validApplyRecordsOnlyARedirectWithTheSelectedOwnedCv() {
        UUID eventId = UUID.randomUUID();
        when(repository.insertExternalApplyEvents(anyMap())).thenReturn(eventId);

        Map<String, Object> result = service.apply(jobId, new CvRequest(resumeId));

        assertEquals(eventId, result.get("event_id"));
        assertEquals(job.get("source_url"), result.get("target_url"));
        verify(repository).insertExternalApplyEvents(
            argThat(
                fields -> "REDIRECT".equals(fields.get("event_type")) && candidate.equals(fields.get("user_id"))
                    && resumeId.equals(fields.get("resume_id")) && jobId.equals(fields.get("market_job_id"))
                    && job.get("source_url").equals(fields.get("target_url")) && !fields.containsKey("submitted_at")
            )
        );
        verifyNoInteractions(ai);
    }

    private Map<String, Object> validGeneration() {
        return Map.of(
            "subject",
            "Java application",
            "email",
            "My factual application email",
            "cover_letter",
            "My factual cover letter"
        );
    }
}
