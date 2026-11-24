package com.example.intelligent_cv_matching_ai_agent.resume;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.resume.ResumeWriteRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.resume.ResumeRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ResumeFileExtractor;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResumeTemplateSelectionTests {

    private final ResumeRepository db = mock(ResumeRepository.class);

    private final CurrentUser current = mock(CurrentUser.class);

    private final AiService ai = mock(AiService.class);

    private final ResumeService service = new ResumeService(
        db,
        current,
        ai,
        mock(ResumeFileExtractor.class),
        JsonMapper.builder().build(),
        "./target/test-storage"
    );

    @Test
    void templateOnlyPatchDoesNotRehashLegacyContentOrInvalidateItsDerivedData() {
        UUID id = UUID.randomUUID(), template = UUID.randomUUID();
        Map<String, Object> resume = new LinkedHashMap<>(Map.of("id", id, "status", "READY", "content_hash", "legacy"));
        when(db.getResumesByIdForUpdate(id)).thenReturn(resume);
        when(db.getResumesById(id)).thenReturn(resume);
        when(db.findResumeTemplateById(template)).thenReturn(Optional.of(Map.of("id", template)));
        when(db.updateResumeTemplateById(template, id)).thenAnswer(call -> {
            resume.put("template_id", template);
            return 1;
        });
        Map<String, Object> result = service.update(id, Map.of("template_id", template.toString()));
        assertEquals("legacy", result.get("content_hash"));
        assertEquals("READY", result.get("status"));
        assertEquals(template, result.get("template_id"));
        verify(db).getResumeTemplatesById(template);
        verify(db, never()).updateResumesById(any(Object[].class));
        verify(db, never()).updateRoadmapsByGapAnalysisId(any(Object[].class));
        verifyNoInteractions(ai);
    }

    @Test
    void templateMetadataIsNotReadBeforeOwnershipHasBeenEstablished() {
        UUID id = UUID.randomUUID(), template = UUID.randomUUID();
        Map<String, Object> row = Map.of("template_id", template);
        when(db.getResumesById(id)).thenReturn(row);
        doThrow(ApiException.notFound("Resource not found")).when(current).owns(row, "user_id");
        assertEquals(404, assertThrows(ApiException.class, () -> service.get(id)).status());
        verify(db, never()).findResumeTemplateById(any(Object[].class));
    }

    @Test
    void templateRequestRetainsOmissionAndExplicitNullAndAcceptsCanonicalUuidOnly() {
        JsonMapper mapper = JsonMapper.builder().build();
        ResumeWriteRequest omitted = mapper.readValue("{}", ResumeWriteRequest.class);
        ResumeWriteRequest cleared = mapper.readValue("{\"template_id\":null}", ResumeWriteRequest.class);
        assertTrue(omitted.isValidShape());
        assertFalse(omitted.toInputMap().containsKey("template_id"));
        assertTrue(cleared.isValidShape());
        assertTrue(cleared.toInputMap().containsKey("template_id"));
        assertNull(cleared.toInputMap().get("template_id"));
        ResumeWriteRequest selected = mapper.readValue(
            "{\"template_id\":\"" + UUID.randomUUID().toString().toUpperCase(java.util.Locale.ROOT) + "\"}",
            ResumeWriteRequest.class
        );
        assertTrue(selected.isValidShape());
        for (String value : new String[]{"\"bad\"", "\"1-1-1-1-1\"", "12", "true", "{}", "[]"}) {
            ResumeWriteRequest invalid = mapper.readValue("{\"template_id\":" + value + "}", ResumeWriteRequest.class);
            assertFalse(invalid.isValidShape(), value);
        }
    }
}
