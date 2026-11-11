package com.example.intelligent_cv_matching_ai_agent.resume;

import com.example.intelligent_cv_matching_ai_agent.config.shared.ResumeStorageConfig;
import com.example.intelligent_cv_matching_ai_agent.config.shared.resume.ResumeStorageProperties;
import com.example.intelligent_cv_matching_ai_agent.dto.response.candidate.resume.ResumeDownloadResponse;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.resume.ResumePdfExportRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.resume.ResumeRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumePdfExportService;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumePdfRenderer;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.resume.FirebaseResumeFileStorage;
import com.example.intelligent_cv_matching_ai_agent.service.shared.resume.LocalResumeFileStorage;
import com.example.intelligent_cv_matching_ai_agent.service.shared.resume.ResumeFileStorage;
import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ResumeFileExtractor;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FirebaseResumeFileStorageTests {

    private static final String BUCKET = "edunexus-cv-bucket.appspot.com";

    private static final String PREFIX = "resumes";

    @Test
    void storesReadsChecksAndDeletesObjectsInFirebaseStorageBucketWithNormalizedPrefix() {
        Storage gcs = mock(Storage.class);
        Map<BlobId, byte[]> objects = new HashMap<>();
        when(gcs.create(any(BlobInfo.class), any(byte[].class), any(Storage.BlobTargetOption.class)))
            .thenAnswer(call -> {
                BlobInfo info = call.getArgument(0);
                byte[] bytes = call.getArgument(1);
                objects.put(info.getBlobId(), bytes);
                Blob blob = mock(Blob.class);
                when(blob.exists()).thenReturn(true);
                when(blob.getSize()).thenReturn((long) bytes.length);
                when(blob.getContent()).thenReturn(bytes);
                return blob;
            });
        when(gcs.get(any(BlobId.class))).thenAnswer(call -> {
            BlobId id = call.getArgument(0);
            byte[] bytes = objects.get(id);
            if (bytes == null) {
                return null;
            }
            Blob blob = mock(Blob.class);
            when(blob.exists()).thenReturn(true);
            when(blob.getSize()).thenReturn((long) bytes.length);
            when(blob.getContent()).thenReturn(bytes);
            return blob;
        });
        when(gcs.delete(any(BlobId.class))).thenAnswer(call -> objects.remove(call.getArgument(0)) != null);

        FirebaseResumeFileStorage storage = new FirebaseResumeFileStorage(gcs, "gs://" + BUCKET + "/", "/resumes/");
        String key = UUID.randomUUID() + ".pdf";
        byte[] content = "%PDF-1.7\nfirebase-cv-bytes".getBytes(StandardCharsets.UTF_8);

        storage.store(key, content, "application/pdf");

        BlobId expectedId = BlobId.of(BUCKET, PREFIX + "/" + key);
        assertTrue(objects.containsKey(expectedId));
        assertTrue(storage.exists(key, 1024 * 1024));
        assertArrayEquals(content, storage.read(key));

        storage.delete(key);
        assertFalse(storage.exists(key, 1024 * 1024));
        assertEquals(404, assertThrows(ApiException.class, () -> storage.read(key)).status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"../secret.pdf", "nested/file.pdf", "not-a-uuid.pdf", "1234.exe", ""})
    void rejectsInvalidKeysBeforeCallingCloudStorage(
        String invalidKey) {
        Storage gcs = mock(Storage.class);
        FirebaseResumeFileStorage storage = new FirebaseResumeFileStorage(gcs, BUCKET, PREFIX);

        assertEquals(
            400,
            assertThrows(ApiException.class, () -> storage.store(invalidKey, new byte[]{1}, "application/pdf")).status()
        );
        assertEquals(400, assertThrows(ApiException.class, () -> storage.read(invalidKey)).status());
        assertFalse(storage.exists(invalidKey, 1024));
        verifyNoInteractions(gcs);
    }

    @Test
    void translatesCloudStorageFailuresIntoApiExceptions() {
        Storage gcs = mock(Storage.class);
        FirebaseResumeFileStorage storage = new FirebaseResumeFileStorage(gcs, BUCKET, PREFIX);
        String key = UUID.randomUUID() + ".pdf";
        when(gcs.create(any(BlobInfo.class), any(byte[].class), any(Storage.BlobTargetOption.class)))
            .thenThrow(new StorageException(500, "Firebase unavailable"));
        when(gcs.get(any(BlobId.class))).thenThrow(new StorageException(404, "Not found"));

        assertEquals(
            503,
            assertThrows(ApiException.class, () -> storage.store(key, new byte[]{1, 2}, "application/pdf")).status()
        );
        assertEquals(404, assertThrows(ApiException.class, () -> storage.read(key)).status());
    }

    @Test
    void resumeUploadAndDownloadWorkThroughFirebaseStorageAndCleanupOnTransactionRollback() {
        Storage gcs = mock(Storage.class);
        Map<BlobId, byte[]> objects = new HashMap<>();
        when(gcs.create(any(BlobInfo.class), any(byte[].class), any(Storage.BlobTargetOption.class)))
            .thenAnswer(call -> {
                BlobInfo info = call.getArgument(0);
                byte[] bytes = call.getArgument(1);
                objects.put(info.getBlobId(), bytes);
                return mock(Blob.class);
            });
        when(gcs.get(any(BlobId.class))).thenAnswer(call -> {
            byte[] bytes = objects.get(call.getArgument(0));
            if (bytes == null) {
                return null;
            }
            Blob blob = mock(Blob.class);
            when(blob.exists()).thenReturn(true);
            when(blob.getContent()).thenReturn(bytes);
            return blob;
        });
        when(gcs.delete(any(BlobId.class))).thenAnswer(call -> objects.remove(call.getArgument(0)) != null);

        FirebaseResumeFileStorage firebaseStorage = new FirebaseResumeFileStorage(gcs, BUCKET, PREFIX);
        SqlStore db = mock(SqlStore.class);
        CurrentUser user = mock(CurrentUser.class);
        AiService ai = mock(AiService.class);
        JsonMapper mapper = JsonMapper.builder().build();
        UUID actor = UUID.randomUUID();
        UUID resumeId = UUID.randomUUID();
        when(user.id()).thenReturn(actor);
        when(db.json(any())).thenAnswer(call -> new SqlStore.JsonValue(mapper.writeValueAsString(call.getArgument(0))));
        Map<String, Object> saved = new LinkedHashMap<>();
        when(db.insert(eq("resumes"), anyMap())).thenAnswer(call -> {
            saved.putAll(call.getArgument(1));
            saved.put("id", resumeId);
            return resumeId;
        });
        when(db.one("SELECT * FROM resumes WHERE id=?", resumeId)).thenAnswer(call -> saved);

        ResumeService service = new ResumeService(
            new ResumeRepository(db),
            user,
            ai,
            new ResumeFileExtractor(false, "", "eng"),
            mapper,
            firebaseStorage
        );
        String raw = "Full name: Minh Tran\nEmail: minh@example.org\nSkills: Java, Spring Boot";

        Map<String, Object> uploaded = service.upload(
            new MockMultipartFile("file", "cv.txt", "text/plain", raw.getBytes(StandardCharsets.UTF_8)),
            "Backend CV",
            "vi"
        );

        String fileKey = uploaded.get("file_url").toString();
        BlobId blobId = BlobId.of(BUCKET, PREFIX + "/" + fileKey);
        assertTrue(objects.containsKey(blobId));
        ResumeDownloadResponse downloaded = service.download(resumeId, false);
        assertEquals("cv.txt", downloaded.filename());
        assertEquals(raw, new String(downloaded.content(), StandardCharsets.UTF_8));

        // Verify rollback cleanup removes the uploaded object from Firebase Storage
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.upload(
                new MockMultipartFile("file", "rollback.txt", "text/plain", raw.getBytes(StandardCharsets.UTF_8)),
                "Rollback CV",
                "vi"
            );
            String rollbackKey = saved.get("file_url").toString();
            BlobId rollbackBlobId = BlobId.of(BUCKET, PREFIX + "/" + rollbackKey);
            assertTrue(objects.containsKey(rollbackBlobId));
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }
            assertFalse(objects.containsKey(rollbackBlobId));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void resumePdfExportAndDownloadStoreAndReusePdfInFirebaseStorage() {
        Storage gcs = mock(Storage.class);
        Map<BlobId, byte[]> objects = new HashMap<>();
        when(gcs.create(any(BlobInfo.class), any(byte[].class), any(Storage.BlobTargetOption.class)))
            .thenAnswer(call -> {
                BlobInfo info = call.getArgument(0);
                byte[] bytes = call.getArgument(1);
                objects.put(info.getBlobId(), bytes);
                return mock(Blob.class);
            });
        when(gcs.get(any(BlobId.class))).thenAnswer(call -> {
            byte[] bytes = objects.get(call.getArgument(0));
            if (bytes == null) {
                return null;
            }
            Blob blob = mock(Blob.class);
            when(blob.exists()).thenReturn(true);
            when(blob.getSize()).thenReturn((long) bytes.length);
            when(blob.getContent()).thenReturn(bytes);
            return blob;
        });

        FirebaseResumeFileStorage firebaseStorage = new FirebaseResumeFileStorage(gcs, BUCKET, PREFIX);
        ResumePdfExportRepository db = mock(ResumePdfExportRepository.class);
        CurrentUser user = mock(CurrentUser.class);
        ResumePdfRenderer renderer = mock(ResumePdfRenderer.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        UUID owner = UUID.randomUUID();
        UUID resumeId = UUID.randomUUID();
        byte[] pdfBytes = pdfBytes();

        Map<String, Object> resume = new LinkedHashMap<>(
            Map.of(
                "id",
                resumeId,
                "user_id",
                owner,
                "status",
                "READY",
                "title",
                "Firebase CV",
                "language",
                "vi",
                "structured_data",
                Map.of("full_name", "Nguyễn Văn A", "summary", "Java Developer")
            )
        );
        when(db.resume(resumeId)).thenAnswer(call -> new LinkedHashMap<>(resume));
        when(db.lock(resumeId)).thenAnswer(call -> new LinkedHashMap<>(resume));
        when(db.template(any())).thenReturn("");
        when(renderer.render(anyMap(), anyString())).thenReturn(pdfBytes);
        when(txManager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(db.save(eq(resumeId), anyString(), anyString(), anyLong(), anyString())).thenAnswer(call -> {
            resume.put("file_url", call.getArgument(1));
            resume.put("file_name", call.getArgument(2));
            resume.put("file_size_bytes", call.getArgument(3));
            resume.put("pdf_input_hash", call.getArgument(4));
            resume.put("mime_type", "application/pdf");
            return 1;
        });

        ResumePdfExportService exportService =
            new ResumePdfExportService(db, user, renderer, JsonMapper.builder().build(), txManager, firebaseStorage);

        Map<String, Object> exported = exportService.export(resumeId);
        String key = exported.get("file_url").toString();
        assertTrue(objects.containsKey(BlobId.of(BUCKET, PREFIX + "/" + key)));

        ResumeDownloadResponse downloaded = exportService.download(resumeId);
        assertArrayEquals(pdfBytes, downloaded.content());
        assertEquals("Firebase_CV.pdf", downloaded.filename());
        verify(renderer, times(1)).render(anyMap(), anyString());
    }

    @Test
    void storageConfigurationSelectsLocalByDefaultAndRequiresBucketWhenFirebaseIsEnabled() {
        ResumeStorageConfig config = new ResumeStorageConfig();
        ResumeStorageProperties props = new ResumeStorageProperties();
        props.setPath("./target/test-local-storage");

        ResumeFileStorage defaultStorage = config.resumeFileStorage(props);
        assertInstanceOf(LocalResumeFileStorage.class, defaultStorage);

        props.setProvider("firebase");
        assertThrows(IllegalStateException.class, () -> config.resumeFileStorage(props));
    }

    private static byte[] pdfBytes() {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            return output.toByteArray();
        } catch (java.io.IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
