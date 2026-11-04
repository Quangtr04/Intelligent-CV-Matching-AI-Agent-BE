package com.example.intelligent_cv_matching_ai_agent.resume;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.ai.AISupportRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.resume.ResumeRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.util.shared.ai.AiResponseData;
import com.example.intelligent_cv_matching_ai_agent.util.shared.ai.CvSupportResponseSchema;
import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ResumeFileExtractor;
import jakarta.validation.Validation;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CvSupportTests {

    @TempDir
    Path storage;

    private final UUID actor = UUID.randomUUID();

    private final ResumeRepository db = mock(ResumeRepository.class);

    private final AiService ai = mock(AiService.class);

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void legacyPayloadRemainsValidAndUsesTheDedicatedFeatureWithoutChangingTheCv() {
        authenticate();
        AISupportRequest request = JsonMapper.builder().build().readValue(
            "{\"question\":[\"Giúp tôi viết phần giới thiệu CV\"],\"is_urgent\":false}",
            AISupportRequest.class
        );
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(request).isEmpty());
        }
        when(ai.generate(eq("CV_AI_SUPPORT"), anyMap())).thenReturn(proposal("Mô tả rõ phần việc đã thực hiện."));
        Map<String, Object> response = service().aiSupport(request);
        assertEquals("Mô tả rõ phần việc đã thực hiện.", response.get("answer"));
        verify(ai).generate(eq("CV_AI_SUPPORT"), argThat(input -> "vi".equals(input.get("language"))));
        verifyNoInteractions(db);
    }

    @Test
    void ownedResumeAndUnsavedSectionArePassedAsFactsWithTheRequestedLanguage() {
        authenticate();
        UUID resumeId = UUID.randomUUID();
        when(db.getResumesById(resumeId)).thenReturn(
            Map.of(
                "id",
                resumeId,
                "user_id",
                actor,
                "language",
                "en",
                "structured_data",
                Map.of("summary", "Reviewed Java developer"),
                "raw_content",
                "Old unreviewed content"
            )
        );
        when(ai.generate(eq("CV_AI_SUPPORT"), anyMap())).thenReturn(proposal("Built Java APIs."));
        service().aiSupport(request(resumeId, "Built Java APIs."));
        verify(ai).generate(eq("CV_AI_SUPPORT"), argThat(input -> {
            Map<?, ?> resume = (Map<?, ?>) input.get("resume");
            Map<?, ?> section = (Map<?, ?>) input.get("section");
            return "en".equals(input.get("language")) && !resume.containsKey("raw_text")
                && "REVIEWED_STRUCTURED".equals(resume.get("content_authority"))
                && "Built Java APIs.".equals(section.get("current_text")) && "experience".equals(section.get("field"));
        }));
        verify(db, only()).getResumesById(resumeId);
    }

    @Test
    void anotherCandidatesResumeIsRejectedBeforeCallingAi() {
        authenticate();
        UUID resumeId = UUID.randomUUID();
        when(db.getResumesById(resumeId)).thenReturn(Map.of("user_id", UUID.randomUUID()));
        ApiException failure = assertThrows(ApiException.class, () -> service().aiSupport(request(resumeId, "Java")));
        assertEquals(404, failure.status());
        verifyNoInteractions(ai);
    }

    @Test
    void schemaAndValidationPreserveSourceQuotesAndMultilineExperienceText() {
        Map<String, Object> input =
            Map.of("section", Map.of("current_text", "Built Java APIs.\nAdded automated tests."));
        Map<?, ?> schema = (Map<?, ?>) AiResponseData.outputFormat("CV_AI_SUPPORT", input);
        assertTrue(((List<?>) schema.get("required")).containsAll(List.of("answer", "suggestions")));
        Map<String, Object> response = Map.of(
            "answer",
            "Built Java APIs.\nAdded automated tests.",
            "suggestions",
            List.of(
                Map.of(
                    "original_content",
                    "Built Java APIs.",
                    "suggested_content",
                    "Built Java APIs with clear responsibilities.",
                    "explanation",
                    "Describe the verified responsibilities."
                )
            )
        );
        assertDoesNotThrow(() -> CvSupportResponseSchema.validateProposal(response, input));
        Map<String, Object> invented = Map.of(
            "answer",
            "Built Java APIs.",
            "suggestions",
            List.of(
                Map.of(
                    "original_content",
                    "I managed a large engineering team.",
                    "suggested_content",
                    "Led the team.",
                    "explanation",
                    "Clarify the role."
                )
            )
        );
        assertThrows(IllegalArgumentException.class, () -> CvSupportResponseSchema.validateProposal(invented, input));
    }

    @Test
    void ChineseProseAndInventedPlaceholdersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> CvSupportResponseSchema.validate(proposal("增加项目细节。")));
        assertThrows(
            IllegalArgumentException.class,
            () -> CvSupportResponseSchema.validate(proposal("Improved by [number]%."))
        );
        assertTrue(AiResponseData.taskInstructions("CV_AI_SUPPORT").contains("never claim the stored CV was changed"));
    }

    private AISupportRequest request(
        UUID resumeId,
        String current) {
        return new AISupportRequest(
            List.of("Improve the experience section."),
            false,
            resumeId,
            null,
            "experience",
            current,
            "Backend Developer",
            List.of("Java"),
            "Developer",
            "Current employer",
            "Java APIs"
        );
    }

    private Map<String, Object> proposal(
        String answer) {
        return Map.of("answer", answer, "suggestions", List.of());
    }

    private void authenticate() {
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken(actor.toString(), "", List.of()));
    }

    private ResumeService service() {
        return new ResumeService(
            db,
            new CurrentUser(),
            ai,
            mock(ResumeFileExtractor.class),
            JsonMapper.builder().build(),
            storage.toString()
        );
    }
}
