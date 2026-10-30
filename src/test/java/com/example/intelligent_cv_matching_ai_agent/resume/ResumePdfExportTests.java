package com.example.intelligent_cv_matching_ai_agent.resume;

import com.example.intelligent_cv_matching_ai_agent.dto.response.candidate.resume.ResumeDownloadResponse;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.resume.ResumePdfExportRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumePdfExportService;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumePdfRenderer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResumePdfExportTests {

    private static final byte[] PDF = "%PDF-1.7\nunit-test-renderer-output".getBytes(StandardCharsets.UTF_8);

    private final ResumePdfExportRepository db = mock(ResumePdfExportRepository.class);

    private final CurrentUser user = mock(CurrentUser.class);

    private final ResumePdfRenderer renderer = mock(ResumePdfRenderer.class);

    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);

    private final UUID owner = UUID.randomUUID(), resumeId = UUID.randomUUID(), templateId = UUID.randomUUID();

    private final Map<String, Object> resume = new LinkedHashMap<>();

    private final AtomicReference<String> template = new AtomicReference<>("<html>selected-template-v1</html>");

    @TempDir
    private Path directory;

    private ResumePdfExportService service;

    @BeforeEach
    void configure() {
        resume.putAll(
            Map.of(
                "id",
                resumeId,
                "user_id",
                owner,
                "status",
                "READY",
                "title",
                "Nguyễn / CV",
                "language",
                "vi",
                "template_id",
                templateId,
                "structured_data",
                Map.of("full_name", "Nguyễn An", "summary", "Reviewed Java experience")
            )
        );
        when(db.resume(resumeId)).thenAnswer(call -> new LinkedHashMap<>(resume));
        when(db.lock(resumeId)).thenAnswer(call -> new LinkedHashMap<>(resume));
        when(db.template(templateId)).thenAnswer(call -> template.get());
        doAnswer(call -> {
            Map<?, ?> row = call.getArgument(0);
            if (!owner.equals(row.get("user_id"))) {
                throw ApiException.notFound("CV not found");
            }
            return null;
        }).when(user).owns(anyMap(), eq("user_id"));
        when(renderer.render(anyMap(), anyString())).thenReturn(PDF);
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(db.save(eq(resumeId), anyString(), anyString(), anyLong(), anyString())).thenAnswer(call -> {
            resume.put("file_url", call.getArgument(1));
            resume.put("file_name", call.getArgument(2));
            resume.put("file_size_bytes", call.getArgument(3));
            resume.put("pdf_input_hash", call.getArgument(4));
            resume.put("mime_type", "application/pdf");
            return 1;
        });
        service =
            new ResumePdfExportService(db, user, renderer, JsonMapper.builder().build(), manager, directory.toString());
    }

    @Test
    void successfulExportPersistsItsStoredPdfAndSelectedTemplate() throws Exception {
        Map<String, Object> exported = service.export(resumeId);

        String key = exported.get("file_url").toString();
        assertTrue(key.matches("[0-9a-f-]{36}\\.pdf"));
        assertArrayEquals(PDF, Files.readAllBytes(directory.resolve(key)));
        assertEquals("application/pdf", exported.get("mime_type"));
        assertEquals("Nguyễn___CV.pdf", exported.get("file_name"));
        assertEquals(PDF.length, ((Number) exported.get("file_size_bytes")).intValue());
        assertEquals(false, exported.get("cached"));
        verify(renderer).render(
            argThat(
                (
                    Map<String, Object> row) -> row.get("structured_data").equals(resume.get("structured_data"))
            ),
            eq(template.get())
        );
        verify(db).save(
            eq(resumeId),
            eq(key),
            eq(exported.get("file_name").toString()),
            eq((long) PDF.length),
            argThat(
                (
                    String hash) -> hash.matches("[0-9a-f]{64}")
            )
        );
        verify(manager).commit(any());
    }

    @Test
    void repeatedExportAndDownloadReuseThePublishedFileWithoutRenderingAgain() {
        Map<String, Object> first = service.export(resumeId);

        Map<String, Object> repeated = service.export(resumeId);
        ResumeDownloadResponse download = service.download(resumeId);

        assertEquals(first.get("file_url"), repeated.get("file_url"));
        assertEquals(true, repeated.get("cached"));
        assertArrayEquals(PDF, download.content());
        assertEquals(first.get("file_name"), download.filename());
        assertEquals("application/pdf", download.mimeType());
        verify(renderer, times(1)).render(anyMap(), anyString());
        verify(db, times(1)).save(any(), anyString(), anyString(), anyLong(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "DRAFT", "REVIEW_REQUIRED", "ARCHIVED"})
    void ownershipAndConfirmationAreCheckedBeforeTheRenderer(
        String invalid) {
        if (invalid.equals("owner")) {
            resume.put("user_id", UUID.randomUUID());
        } else {
            resume.put("status", invalid);
        }

        assertThrows(ApiException.class, () -> service.export(resumeId));

        verifyNoInteractions(renderer, manager);
        verify(db, never()).save(any(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void ownershipChecksAlsoRunBeforeReturningAnAlreadyExportedFile() {
        service.export(resumeId);
        resume.put("user_id", UUID.randomUUID());
        clearInvocations(renderer);

        assertEquals(404, assertThrows(ApiException.class, () -> service.download(resumeId)).status());

        verifyNoInteractions(renderer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"content", "raw", "title", "language", "template"})
    void changedContentOrTemplateProducesANewFile(
        String change) {
        Map<String, Object> first = service.export(resumeId);
        switch (change) {
            case "content" -> resume.put("structured_data", Map.of("summary", "Updated reviewed CV"));
            case "raw" -> resume.put("raw_content", "New original extraction");
            case "title" -> resume.put("title", "New CV title");
            case "language" -> resume.put("language", "en");
            case "template" -> template.set("<html>selected-template-v2</html>");
            default -> fail("Unknown input");
        }

        Map<String, Object> next = service.export(resumeId);

        assertNotEquals(first.get("file_url"), next.get("file_url"));
        assertEquals(false, next.get("cached"));
        verify(renderer, times(2)).render(anyMap(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"content", "status", "owner", "template"})
    void concurrentChangesRejectPublicationAndDeleteOnlyTheUnpublishedFile(
        String change)
        throws Exception {
        Path unrelated = directory.resolve("unrelated-source.txt");
        Files.writeString(unrelated, "Original upload must survive");
        when(renderer.render(anyMap(), anyString())).thenAnswer(call -> {
            switch (change) {
                case "content" -> resume.put("structured_data", Map.of("summary", "Edited during rendering"));
                case "status" -> resume.put("status", "REVIEW_REQUIRED");
                case "owner" -> resume.put("user_id", UUID.randomUUID());
                case "template" -> template.set("<html>edited during render</html>");
                default -> fail("Unknown race");
            }
            return PDF;
        });

        assertThrows(ApiException.class, () -> service.export(resumeId));

        assertEquals("Original upload must survive", Files.readString(unrelated));
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
        verify(db, never()).save(any(), anyString(), anyString(), anyLong(), anyString());
        verify(manager).rollback(any());
    }

    @Test
    void rendererFailureCannotPublishDatabaseMetadataOrCreateAnOutput() throws Exception {
        when(renderer.render(anyMap(), anyString())).thenThrow(new ApiException(503, "Renderer unavailable"));

        assertEquals(503, assertThrows(ApiException.class, () -> service.export(resumeId)).status());

        try (var files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
        verifyNoInteractions(manager);
        verify(db, never()).save(any(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void failedPublicationRollsBackAndRemovesTheUnpublishedPdf() throws Exception {
        when(db.save(eq(resumeId), anyString(), anyString(), anyLong(), anyString())).thenReturn(0);

        assertEquals(409, assertThrows(ApiException.class, () -> service.export(resumeId)).status());

        try (var files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
        assertNull(resume.get("file_url"));
        verify(manager).rollback(any());
        verify(manager, never()).commit(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"../source.pdf", "..\\source.pdf", "C:/private/source.pdf", "https://example.test/cv.pdf"})
    void injectedStoredPathsAreNeverReadAndAreReplacedWithASafeStorageKey(
        String malicious)
        throws Exception {
        service.export(resumeId);
        resume.put("file_url", malicious);
        clearInvocations(renderer);

        Map<String, Object> repaired = service.export(resumeId);

        String safeKey = repaired.get("file_url").toString();
        assertTrue(safeKey.matches("[0-9a-f-]{36}\\.pdf"));
        assertEquals(directory.toAbsolutePath().normalize(), directory.resolve(safeKey).normalize().getParent());
        assertArrayEquals(PDF, Files.readAllBytes(directory.resolve(safeKey)));
        assertEquals(false, repaired.get("cached"));
        verify(renderer).render(anyMap(), anyString());
    }

    @Test
    void missingStoredOutputIsRegeneratedInsteadOfReturningABrokenLink() throws Exception {
        Map<String, Object> first = service.export(resumeId);
        Files.delete(directory.resolve(first.get("file_url").toString()));

        Map<String, Object> repaired = service.export(resumeId);

        assertNotEquals(first.get("file_url"), repaired.get("file_url"));
        assertEquals(false, repaired.get("cached"));
        verify(renderer, times(2)).render(anyMap(), anyString());
    }

    @Test
    void repositoryPreservesTheOriginalUploadWhilePublishingPdfMetadata() {
        SqlStore store = mock(SqlStore.class);
        ResumePdfExportRepository repository = new ResumePdfExportRepository(store);

        repository.save(resumeId, "export.pdf", "CV.pdf", PDF.length, "input-hash");

        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(store).update(
            query.capture(),
            eq("export.pdf"),
            eq("CV.pdf"),
            eq((long) PDF.length),
            eq("input-hash"),
            eq(resumeId)
        );
        assertTrue(query.getValue().contains("original_file_url=COALESCE(original_file_url"));
        assertTrue(query.getValue().contains("source='UPLOAD' AND pdf_input_hash IS NULL THEN file_url"));
        assertTrue(query.getValue().contains("status='READY'"));
    }
}
