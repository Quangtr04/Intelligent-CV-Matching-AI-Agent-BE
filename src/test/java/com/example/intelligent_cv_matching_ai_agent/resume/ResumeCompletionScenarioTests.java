package com.example.intelligent_cv_matching_ai_agent.resume;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.resume.ResumeRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ResumeFileExtractor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResumeCompletionScenarioTests {

    private final ResumeRepository repository = mock(ResumeRepository.class);

    private final AiService ai = mock(AiService.class);

    private final UUID candidate = UUID.randomUUID();

    private final UUID resumeId = UUID.randomUUID();

    private final Map<String, Object> resume = new LinkedHashMap<>();

    private final ResumeService service = new ResumeService(
        repository,
        new CurrentUser(),
        ai,
        mock(ResumeFileExtractor.class),
        JsonMapper.builder().build(),
        "./target/scenario-test-storage"
    );

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken(candidate.toString(), null, List.of()));
        resume.putAll(
            Map.of(
                "id",
                resumeId,
                "user_id",
                candidate,
                "title",
                "Reviewed Java CV",
                "language",
                "en",
                "status",
                "DRAFT",
                "structured_data",
                Map.of("summary", "Built Java APIs"),
                "raw_content",
                "",
                "content_hash",
                "old-hash"
            )
        );
        when(repository.getResumesById(resumeId)).thenReturn(resume);
        when(repository.json(any())).thenAnswer(call -> call.getArgument(0));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void manualCreationStoresAnOwnedDraftWithoutCallingAi() {
        when(repository.insertResumes(anyMap())).thenReturn(resumeId);

        service.create(
            Map.of("title", "My Java CV", "language", "en", "structured_data", Map.of("summary", "Built Java APIs")),
            false
        );

        verify(repository).insertResumes(
            argThat(
                fields -> candidate.equals(fields.get("user_id")) && "DRAFT".equals(fields.get("status"))
                    && "MANUAL".equals(fields.get("source")) && "My Java CV".equals(fields.get("title"))
            )
        );
        verifyNoInteractions(ai);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "REVIEW_REQUIRED"})
    void nonemptyOwnedCvCanBeConfirmedFromEitherReviewState(
        String status) {
        resume.put("status", status);

        service.confirm(resumeId, Map.of());

        verify(repository).getUsersByIdForUpdate(candidate);
        verify(repository).updateResumesById2(any(), eq(""), anyString(), eq(false), eq(resumeId));
        verify(repository, never()).updateResumesByUserId(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "REVIEW_REQUIRED", "READY"})
    void emptyCvCannotPublishOrReplaceAnySavedSections(
        String status) {
        resume.put("status", status);
        resume.put("structured_data", Map.of());

        ApiException failure = assertThrows(ApiException.class, () -> service.confirm(resumeId, Map.of()));

        assertEquals(400, failure.status());
        verify(repository, never()).updateResumesById2(any(), any(), any(), any(), any());
        verify(repository, never()).deleteSection(anyString(), any());
        verify(repository, never()).deleteResumeSkillsByResumeId(any());
        verify(repository, never()).updateResumesByUserId(any());
        verifyNoInteractions(ai);
    }

    @Test
    void archivedCvCannotBeConfirmedOrEdited() {
        resume.put("status", "ARCHIVED");

        assertEquals(409, assertThrows(ApiException.class, () -> service.confirm(resumeId, Map.of())).status());
        assertEquals(
            409,
            assertThrows(ApiException.class, () -> service.update(resumeId, Map.of("title", "Changed"))).status()
        );

        verify(repository, never()).updateResumesById2(any(), any(), any(), any(), any());
        verify(repository, never()).updateResumesById(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void foreignCvCannotConfirmOrReplaceTheCurrentPrimary() {
        resume.put("user_id", UUID.randomUUID());

        assertEquals(
            404,
            assertThrows(ApiException.class, () -> service.confirm(resumeId, Map.of("is_primary", true))).status()
        );

        verify(repository, never()).updateResumesByUserId(any());
        verify(repository, never()).updateResumesById2(any(), any(), any(), any(), any());
        verifyNoInteractions(ai);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "REVIEW_REQUIRED", "ARCHIVED"})
    void onlyReadyCvCanReplaceThePrimaryCv(
        String status) {
        resume.put("status", status);

        assertEquals(409, assertThrows(ApiException.class, () -> service.primary(resumeId)).status());

        verify(repository, never()).updateResumesByUserId2(any());
        verify(repository, never()).updateResumesById3(any());
    }

    @Test
    void settingPrimaryLocksOwnerThenClearsOnlyThatOwnersPreviousPrimary() {
        resume.put("status", "READY");

        service.primary(resumeId);

        var order = inOrder(repository);
        order.verify(repository).getUsersByIdForUpdate(candidate);
        order.verify(repository).getResumesById(resumeId);
        order.verify(repository).updateResumesByUserId2(candidate);
        order.verify(repository).updateResumesById3(resumeId);
    }

    @Test
    void confirmingAnExistingPrimaryKeepsItPrimaryWithoutAnExplicitFlag() {
        resume.put("is_primary", true);

        service.confirm(resumeId, Map.of());

        var order = inOrder(repository);
        order.verify(repository).getUsersByIdForUpdate(candidate);
        order.verify(repository).updateResumesByUserId(candidate);
        order.verify(repository).updateResumesById2(any(), eq(""), anyString(), eq(true), eq(resumeId));
    }

    @Test
    void ownedReadyCvCanBeArchivedAndInvalidatesOnlyItsDerivedData() {
        resume.put("status", "READY");

        service.archive(resumeId);

        var order = inOrder(repository);
        order.verify(repository).getResumesById(resumeId);
        order.verify(repository).updateResumesById4(resumeId);
        order.verify(repository).updateResumeEmbeddingsByResumeId(resumeId);
        order.verify(repository).updateRoadmapsByGapAnalysisId(resumeId);
        verify(repository, never()).updateResumesByUserId(any());
        verifyNoInteractions(ai);
    }
}
