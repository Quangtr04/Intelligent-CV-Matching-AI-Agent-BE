package com.example.intelligent_cv_matching_ai_agent.resume;

import com.example.intelligent_cv_matching_ai_agent.repository.candidate.resume.ResumeRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.resume.ResumePdfHtml;
import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ExtractedResume;
import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ResumeFileExtractor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Upload state only; extractor is mocked because real PDF/DOCX parsing has its own fixture tests. */
class ResumeUploadPdfStateTests {

    private final ResumeRepository db = mock(ResumeRepository.class);

    private final CurrentUser current = mock(CurrentUser.class);

    private final AiService ai = mock(AiService.class);

    private final ResumeFileExtractor extractor = mock(ResumeFileExtractor.class);

    private final JsonMapper mapper = JsonMapper.builder().build();

    private final UUID owner = UUID.randomUUID();

    private final UUID id = UUID.randomUUID();

    private final Map<String, Object> saved = new LinkedHashMap<>();

    @TempDir
    private Path storage;

    private ResumeService service;

    @BeforeEach
    void setup() {
        when(current.id()).thenReturn(owner);
        when(db.json(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(db.insertResumes(anyMap())).thenAnswer(invocation -> {
            saved.putAll(invocation.getArgument(0));
            saved.put("id", id);
            return id;
        });
        when(db.getResumesById(id)).thenAnswer(invocation -> new LinkedHashMap<>(saved));
        service = new ResumeService(db, current, ai, extractor, mapper, storage.toString());
    }

    @Test
    void pdfUploadPreservesOriginalBytesAndTheEffectiveInputBaselineWithoutChangingItsOrigin() throws Exception {
        byte[] bytes = "%PDF-1.7\noriginal upload fixture".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "Candidate CV.pdf", "application/pdf", bytes);
        String text = "Full name: Demo Candidate\nSummary: Reviewed Java experience\nSkills: Java, SQL";
        when(extractor.extract(file))
            .thenReturn(new ExtractedResume(bytes, "Candidate CV.pdf", "application/pdf", text, false, "PDF"));

        Map<String, Object> result = service.upload(file, null, "en");

        assertEquals("UPLOAD", result.get("source"));
        assertEquals("REVIEW_REQUIRED", result.get("status"));
        assertEquals(result.get("file_url"), result.get("original_file_url"));
        assertTrue(result.get("file_url").toString().matches("[0-9a-f-]{36}\\.pdf"));
        assertArrayEquals(bytes, Files.readAllBytes(storage.resolve(result.get("original_file_url").toString())));
        assertEquals("Candidate CV.pdf", result.get("file_name"));
        assertEquals("application/pdf", result.get("mime_type"));
        assertEquals(bytes.length, result.get("file_size_bytes"));
        assertEquals(ResumePdfHtml.inputHash(mapper, saved, ""), result.get("pdf_input_hash"));
        assertTrue(result.get("pdf_input_hash").toString().matches("[0-9a-f]{64}"));
        verify(db).insertResumeParseResults(
            argThat(parse -> text.equals(parse.get("raw_text")) && "COMPLETED".equals(parse.get("status")))
        );
        verifyNoInteractions(ai);
    }

    @Test
    void docxUploadKeepsTheOriginalPrivateFileWithoutClaimingItAlreadyHasAPdf() throws Exception {
        byte[] bytes = "Synthetic DOCX extractor fixture".getBytes(StandardCharsets.UTF_8);
        String mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        MockMultipartFile file = new MockMultipartFile("file", "Candidate CV.docx", mime, bytes);
        when(extractor.extract(file))
            .thenReturn(new ExtractedResume(bytes, "Candidate CV.docx", mime, "Skills: Java, SQL", false, "DOCX"));

        Map<String, Object> result = service.upload(file, "Reviewed uploaded CV", "vi");

        assertEquals("UPLOAD", result.get("source"));
        assertEquals("REVIEW_REQUIRED", result.get("status"));
        assertEquals(result.get("file_url"), result.get("original_file_url"));
        assertTrue(result.get("original_file_url").toString().endsWith(".docx"));
        assertArrayEquals(bytes, Files.readAllBytes(storage.resolve(result.get("original_file_url").toString())));
        assertEquals(mime, result.get("mime_type"));
        assertNull(result.get("pdf_input_hash"));
        assertEquals("Reviewed uploaded CV", result.get("title"));
        verifyNoInteractions(ai);
    }
}
