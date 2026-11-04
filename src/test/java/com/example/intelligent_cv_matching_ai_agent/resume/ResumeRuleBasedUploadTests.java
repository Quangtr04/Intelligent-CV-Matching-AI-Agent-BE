package com.example.intelligent_cv_matching_ai_agent.resume;

import com.example.intelligent_cv_matching_ai_agent.repository.candidate.resume.ResumeRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ResumeFileExtractor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResumeRuleBasedUploadTests {

    @TempDir
    Path storage;

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void uploadPersistsExtractedFieldsAndOriginalTextWithoutCallingAi() throws Exception {
        SqlStore db = mock(SqlStore.class);
        CurrentUser user = mock(CurrentUser.class);
        AiService ai = mock(AiService.class);
        JsonMapper mapper = JsonMapper.builder().build();
        UUID actor = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(user.id()).thenReturn(actor);
        when(db.json(any())).thenAnswer(call -> new SqlStore.JsonValue(mapper.writeValueAsString(call.getArgument(0))));
        Map<String, Object> saved = new LinkedHashMap<>();
        when(db.insert(eq("resumes"), anyMap())).thenAnswer(call -> {
            saved.putAll(call.getArgument(1));
            saved.put("id", id);
            return id;
        });
        when(db.one("SELECT * FROM resumes WHERE id=?", id)).thenAnswer(call -> saved);
        ResumeService service = new ResumeService(
            new ResumeRepository(db),
            user,
            ai,
            new ResumeFileExtractor(false, "", "eng"),
            mapper,
            storage.toString()
        );
        String raw = "Full name: Alice Nguyen\nEmail: alice@example.org\nSkills: Java, PostgreSQL";

        Map<String, Object> response = service.upload(
            new MockMultipartFile("file", "cv.txt", "text/plain", raw.getBytes(StandardCharsets.UTF_8)),
            null,
            "en"
        );

        assertEquals(id, response.get("id"));
        assertEquals("REVIEW_REQUIRED", response.get("status"));
        assertEquals(raw, saved.get("raw_content"));
        Map<String, Object> document =
            mapper.readValue(((SqlStore.JsonValue) saved.get("structured_data")).content(), Map.class);
        assertEquals("Alice Nguyen", document.get("full_name"));
        assertEquals("alice@example.org", document.get("email"));
        assertTrue(document.get("skills").toString().contains("PostgreSQL"));
        assertFalse(saved.get("content_hash").toString().isBlank());
        assertEquals(raw, Files.readString(storage.resolve(saved.get("file_url").toString())));

        ArgumentCaptor<Map<String, Object>> parse = ArgumentCaptor.forClass((Class) Map.class);
        verify(db).insert(eq("resume_parse_results"), parse.capture());
        assertEquals("TXT_RULES", parse.getValue().get("parser_name"));
        assertEquals("COMPLETED", parse.getValue().get("status"));
        assertEquals(raw, parse.getValue().get("raw_text"));
        assertEquals(saved.get("structured_data"), parse.getValue().get("normalized_data"));
        verifyNoInteractions(ai);
    }
}
