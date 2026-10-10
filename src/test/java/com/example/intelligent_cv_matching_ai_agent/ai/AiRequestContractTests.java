package com.example.intelligent_cv_matching_ai_agent.ai;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.ai.AssistantFeedbackRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.ai.AssistantMessageRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.ai.CreateAssistantConversationRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.rag.CreateRagDocumentRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.guest.ai.GuestBuildRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.resume.ResumeWriteRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.resume.ResumeRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;
import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ResumeFileExtractor;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AiRequestContractTests {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void trialInputIgnoresInjectedPrivateCvAndClientTokenClaims() {
        UUID template = UUID.randomUUID(), demo = UUID.randomUUID();
        GuestBuildRequest request = json.readValue(
            "{\"template_id\":\"" + template + "\",\"demo_resume_id\":\"" + demo
                + "\",\"structured_data\":{\"summary\":\"invented\"},\"tokens\":0}",
            GuestBuildRequest.class
        );
        assertEquals(template, request.templateId());
        assertEquals(demo, request.demoResumeId());
        assertFalse(json.writeValueAsString(request).contains("invented"));
        assertFalse(json.writeValueAsString(request).contains("tokens"));
    }

    @Test
    void cvDtoPreservesPatchPresenceAndExtensibleBuilderFacts() {
        ResumeWriteRequest request = json.readValue(
            ("{\"language\":null,\"structured_data\":{\"summary\":\"Facts\"},"
                + "\"basic_information\":{\"goal\":\"Java\"}}"),
            ResumeWriteRequest.class
        );
        Map<String, Object> input = request.toInputMap();
        assertFalse(input.containsKey("title"));
        assertTrue(input.containsKey("language"));
        assertNull(input.get("language"));
        assertEquals(Map.of("goal", "Java"), input.get("basic_information"));
        input.put("title", "Modified outside DTO");
        assertFalse(request.toInputMap().containsKey("title"));
        assertTrue(request.isValidShape());
    }

    @Test
    void cvDtoRejectsMalformedStructuredDataBeforeBusinessWork() {
        ResumeWriteRequest request =
            json.readValue("{\"structured_data\":[\"invalid\"],\"is_primary\":\"true\"}", ResumeWriteRequest.class);
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertFalse(factory.getValidator().validate(request).isEmpty());
        }
    }

    @Test
    void explicitNullPrimaryFlagPreservesTheExistingConfirmationContract() {
        ResumeWriteRequest request = json.readValue("{\"is_primary\":null}", ResumeWriteRequest.class);
        assertTrue(request.toInputMap().containsKey("is_primary"));
        assertNull(request.toInputMap().get("is_primary"));
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(request).isEmpty());
        }
        assertFalse(Boolean.TRUE.equals(request.toInputMap().get("is_primary")));
    }

    @Test
    void fractionalAssistantFeedbackIsNeverTruncatedIntoAnInteger() {
        AssistantFeedbackRequest request =
            json.readValue("{\"rating\":3.5,\"is_helpful\":true}", AssistantFeedbackRequest.class);
        assertEquals(3.5, request.rating().doubleValue());
        assertFalse(request.isWholeRating());
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertFalse(factory.getValidator().validate(request).isEmpty());
        }
    }

    @Test
    void assistantContextSnakeCaseFieldsKeepTheirUuidValues() {
        UUID resume = UUID.randomUUID();
        CreateAssistantConversationRequest request = json.readValue(
            "{\"context_type\":\"resume\",\"related_resume_id\":\"" + resume + "\"}",
            CreateAssistantConversationRequest.class
        );
        assertEquals(Map.of("context_type", "resume", "related_resume_id", resume), request.toInputMap());
    }

    @Test
    void absentAssistantRetrieveIsAcceptedAndExplicitNullIsRejected() {
        AssistantMessageRequest absent = json
            .readValue("{\"message\":\"Career question\",\"unexpected\":\"ignored\"}", AssistantMessageRequest.class);
        AssistantMessageRequest explicitNull =
            json.readValue("{\"message\":\"Career question\",\"retrieve\":null}", AssistantMessageRequest.class);
        assertFalse(absent.toInputMap().containsKey("retrieve"));
        assertTrue(explicitNull.toInputMap().containsKey("retrieve"));
        assertNull(explicitNull.toInputMap().get("retrieve"));
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(absent).isEmpty());
            assertFalse(factory.getValidator().validate(explicitNull).isEmpty());
        }
    }

    @Test
    void absentAssistantTitleAndContextKeepDefaultsButExplicitNullIsRejected() {
        CreateAssistantConversationRequest absent = json.readValue("{}", CreateAssistantConversationRequest.class);
        assertTrue(absent.toInputMap().isEmpty());
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(absent).isEmpty());
            for (String field : new String[]{"title", "context_type"}) {
                CreateAssistantConversationRequest explicitNull =
                    json.readValue("{\"" + field + "\":null}", CreateAssistantConversationRequest.class);
                assertTrue(explicitNull.toInputMap().containsKey(field));
                assertNull(explicitNull.toInputMap().get(field));
                assertFalse(factory.getValidator().validate(explicitNull).isEmpty());
            }
        }
    }

    @Test
    void absentRagMetadataAndSourceKeepDefaultsButExplicitNullIsRejected() {
        String required = "\"title\":\"Reference\",\"content\":\"Career knowledge\"";
        CreateRagDocumentRequest absent = json.readValue("{" + required + "}", CreateRagDocumentRequest.class);
        assertFalse(absent.toInputMap().containsKey("metadata"));
        assertFalse(absent.toInputMap().containsKey("sourceType"));
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(absent).isEmpty());
            for (String field : new String[]{"metadata", "sourceType"}) {
                CreateRagDocumentRequest explicitNull =
                    json.readValue("{" + required + ",\"" + field + "\":null}", CreateRagDocumentRequest.class);
                assertTrue(explicitNull.toInputMap().containsKey(field));
                assertNull(explicitNull.toInputMap().get(field));
                assertFalse(factory.getValidator().validate(explicitNull).isEmpty());
            }
        }
    }

    @Test
    void nullCvRawContentDefaultsToEmptyOnCreateAndIsRejectedOnPatch() {
        ResumeWriteRequest request = json.readValue(
            ("{\"title\":\"Manual CV\",\"structured_data\":{\"summary\":\"Verified "
                + "facts\"},\"raw_content\":null}"),
            ResumeWriteRequest.class
        );
        assertTrue(request.toInputMap().containsKey("raw_content"));
        assertNull(request.toInputMap().get("raw_content"));
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(request).isEmpty());
        }
        SqlStore db = mock(SqlStore.class);
        CurrentUser user = mock(CurrentUser.class);
        UUID actor = UUID.randomUUID(), resume = UUID.randomUUID();
        when(user.id()).thenReturn(actor);
        when(db.json(any())).thenReturn(new SqlStore.JsonValue("{}"));
        when(db.insert(eq("resumes"), anyMap())).thenAnswer(call -> {
            Map<String, Object> values = call.getArgument(1);
            assertEquals("", values.get("raw_content"));
            return resume;
        });
        when(db.one("SELECT * FROM resumes WHERE id=?", resume)).thenReturn(
            Map.of(
                "id",
                resume,
                "user_id",
                actor,
                "title",
                "Manual CV",
                "language",
                "vi",
                "status",
                "DRAFT",
                "structured_data",
                Map.of("summary", "Verified facts"),
                "raw_content",
                "initial",
                "content_hash",
                "prior"
            )
        );
        ResumeService service = new ResumeService(
            new ResumeRepository(db),
            user,
            mock(AiService.class),
            mock(ResumeFileExtractor.class),
            json,
            "./target/test-storage"
        );
        service.create(request.toInputMap(), false);
        assertThrows(ApiException.class, () -> service.update(resume, request.toInputMap()));
        verify(db, never()).update(anyString(), any(Object[].class));
    }
}
