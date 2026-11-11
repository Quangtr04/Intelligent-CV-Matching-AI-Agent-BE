package com.example.intelligent_cv_matching_ai_agent.service.candidate.resume;

import com.example.intelligent_cv_matching_ai_agent.dto.response.candidate.resume.ResumeDownloadResponse;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.resume.ResumePdfExportRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.shared.resume.LocalResumeFileStorage;
import com.example.intelligent_cv_matching_ai_agent.service.shared.resume.ResumeFileStorage;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.resume.ResumePdfHtml;
import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.PdfSignature;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.pdfbox.Loader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

@Service
public class ResumePdfExportService {

    private static final int MAX_PDF_BYTES = 20 * 1024 * 1024;

    // Bounded per-process locks coalesce concurrent downloads of the same CV.
    // Publication/read-back still use a DB row lock across application replicas.
    private static final ReentrantLock[] LOCKS = new ReentrantLock[64];

    static {
        java.util.Arrays.setAll(LOCKS, index -> new ReentrantLock());
    }

    private final ResumePdfExportRepository db;

    private final CurrentUser current;

    private final ResumePdfRenderer renderer;

    private final ObjectMapper mapper;

    private final ResumeFileStorage storage;

    private final TransactionTemplate transactions;

    public ResumePdfExportService(
        ResumePdfExportRepository db,
        CurrentUser current,
        ResumePdfRenderer renderer,
        ObjectMapper mapper,
        PlatformTransactionManager manager,
        String storage) {
        this(db, current, renderer, mapper, manager, new LocalResumeFileStorage(storage));
    }

    @Autowired
    public ResumePdfExportService(
        ResumePdfExportRepository db,
        CurrentUser current,
        ResumePdfRenderer renderer,
        ObjectMapper mapper,
        PlatformTransactionManager manager,
        ResumeFileStorage storage) {
        this.db = db;
        this.current = current;
        this.renderer = renderer;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(manager);
        this.storage = storage;
    }

    public Map<String, Object> export(
        UUID id) {
        ReentrantLock lock = lockFor(id);
        lock.lock();
        try {
            return exportLocked(id);
        } finally {
            lock.unlock();
        }
    }

    private Map<String, Object> exportLocked(
        UUID id) {
        Map<String, Object> resume = requireReady(db.resume(id));
        String template = template(resume);
        String fingerprint = fingerprint(resume, template);
        if (fingerprint.equals(resume.get("pdf_input_hash")) && existingPdf(resume)) {
            return response(resume, true);
        }
        // Rendering holds no database lock. Publication checks the exact inputs again.
        byte[] bytes = renderer.render(resume, template);
        if (!validPdf(bytes)) {
            throw new ApiException(503, "CV renderer did not produce a valid PDF");
        }
        String key = validatePdfKey(UUID.randomUUID() + ".pdf");
        String filename = Objects.toString(resume.get("title"), "CV").replaceAll("[^\\p{L}\\p{N}_-]", "_");
        filename = filename.substring(0, Math.min(filename.length(), 180)) + ".pdf";
        AtomicBoolean publicationAttempted = new AtomicBoolean();
        boolean stored = false;
        try {
            storage.store(key, bytes, "application/pdf");
            stored = true;
            String downloadName = filename;
            AtomicReference<Map<String, Object>> replaced = new AtomicReference<>();
            AtomicBoolean registeredCleanup = new AtomicBoolean();
            Map<String, Object> saved = transactions.execute(status -> {
                Map<String, Object> latest = requireReady(db.lock(id));
                if (!fingerprint.equals(fingerprint(latest, template(latest)))) {
                    throw ApiException.conflict("CV or template changed during PDF export; confirm and retry");
                }
                // Another replica may have published while this instance rendered.
                if (fingerprint.equals(latest.get("pdf_input_hash")) && existingPdf(latest)) {
                    return response(latest, true);
                }
                if (db.save(id, key, downloadName, bytes.length, fingerprint) != 1) {
                    throw ApiException.conflict("CV is no longer ready for PDF export");
                }
                publicationAttempted.set(true);
                Map<String, Object> published = new LinkedHashMap<>(latest);
                published.put("file_url", key);
                published.put("file_name", downloadName);
                published.put("mime_type", "application/pdf");
                published.put("file_size_bytes", bytes.length);
                published.put("pdf_input_hash", fingerprint);
                replaced.set(latest);
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    registeredCleanup.set(true);
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

                        @Override
                        public void afterCommit() {
                            deleteReplacedPdf(latest, key);
                        }

                        @Override
                        public void afterCompletion(
                            int completion) {
                            if (completion == STATUS_ROLLED_BACK) {
                                deleteFailedExport(key);
                            }
                        }
                    });
                }
                return response(published, false);
            });
            if (!key.equals(saved.get("file_url"))) {
                deleteFailedExport(key);
            } else if (!registeredCleanup.get()) {
                // TransactionTemplate has committed; also supports non-synchronizing test managers.
                deleteReplacedPdf(replaced.get(), key);
            }
            return saved;
        } catch (ApiException exception) {
            if (stored && !publicationAttempted.get()) {
                deleteFailedExport(key);
            }
            if (exception.status() == 503) {
                throw new ApiException(503, "CV PDF storage is unavailable");
            }
            throw exception;
        } catch (RuntimeException exception) {
            // A post-commit callback can fail after the new path is already durable.
            // Known rollbacks remove the output through afterCompletion; an uncertain
            // commit must retain the file rather than leave the DB pointing at nothing.
            if (stored && !publicationAttempted.get()) {
                deleteFailedExport(key);
            }
            throw exception;
        }
    }

    public ResumeDownloadResponse download(
        UUID id) {
        ReentrantLock lock = lockFor(id);
        lock.lock();
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                exportLocked(id);
                ResumeDownloadResponse file = transactions.execute(status -> {
                    Map<String, Object> latest = requireReady(db.lock(id));
                    if (!fingerprint(latest, template(latest)).equals(latest.get("pdf_input_hash"))) {
                        return null;
                    }
                    byte[] bytes = readPdf(latest);
                    return bytes == null
                        ? null
                        : new ResumeDownloadResponse(bytes, latest.get("file_name").toString(), "application/pdf");
                });
                if (file != null) {
                    return file;
                }
            }
            throw ApiException.conflict("CV changed or its PDF became unavailable; retry download");
        } finally {
            lock.unlock();
        }
    }

    private Map<String, Object> requireReady(
        Map<String, Object> resume) {
        current.owns(resume, "user_id");
        if (!"READY".equals(resume.get("status"))) {
            throw ApiException.conflict("Confirm the latest CV content before exporting PDF");
        }
        return resume;
    }

    private String template(
        Map<String, Object> resume) {
        return db.template(resume.get("template_id") instanceof UUID id ? id : null);
    }

    private String fingerprint(
        Map<String, Object> resume,
        String template) {
        return ResumePdfHtml.inputHash(mapper, resume, template);
    }

    private boolean existingPdf(
        Map<String, Object> resume) {
        return readPdf(resume) != null;
    }

    private byte[] readPdf(
        Map<String, Object> resume) {
        try {
            String key = validatePdfKey(Objects.toString(resume.get("file_url"), ""));
            if (!"application/pdf".equals(resume.get("mime_type")) || !storage.exists(key, MAX_PDF_BYTES)) {
                return null;
            }
            byte[] bytes = storage.read(key);
            if (bytes == null
                || (resume.get("file_size_bytes") instanceof Number size && size.longValue() != bytes.length)) {
                return null;
            }
            return validPdf(bytes) ? bytes : null;
        } catch (ApiException exception) {
            return null;
        }
    }

    private static boolean validPdf(
        byte[] bytes) {
        if (bytes == null || bytes.length > MAX_PDF_BYTES || !PdfSignature.isValid(bytes)) {
            return false;
        }
        try (var document = Loader.loadPDF(bytes)) {
            return !document.isEncrypted() && document.getNumberOfPages() > 0 && document.getNumberOfPages() <= 50;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private static ReentrantLock lockFor(
        UUID id) {
        return LOCKS[Math.floorMod(id.hashCode(), LOCKS.length)];
    }

    private void deleteReplacedPdf(
        Map<String, Object> previous,
        String currentKey) {
        if (previous == null) {
            return;
        }
        String oldKey = Objects.toString(previous.get("file_url"), "");
        if (oldKey.equals(currentKey) || oldKey.equals(previous.get("original_file_url"))
            || ("UPLOAD".equals(previous.get("source")) && previous.get("original_file_url") == null)) {
            return;
        }
        try {
            deleteFailedExport(validatePdfKey(oldKey));
        } catch (ApiException ignored) {
            // Untrusted legacy paths and upload originals are never removed.
        }
    }

    private static String validatePdfKey(
        String key) {
        if (key == null || !key.matches("[0-9a-f-]{36}\\.pdf")) {
            throw ApiException.badRequest("Invalid PDF storage key");
        }
        return key;
    }

    private static Map<String, Object> response(
        Map<String, Object> resume,
        boolean cached) {
        Map<String, Object> response = new LinkedHashMap<>();
        for (String field : java.util.List.of("id", "file_url", "file_name", "mime_type", "file_size_bytes")) {
            response.put(field, resume.get(field));
        }
        response.put("cached", cached);
        return response;
    }

    private void deleteFailedExport(
        String key) {
        try {
            storage.delete(validatePdfKey(key));
        } catch (RuntimeException ignored) {
            // Best-effort cleanup must never invalidate a successful publication.
        }
    }
}
