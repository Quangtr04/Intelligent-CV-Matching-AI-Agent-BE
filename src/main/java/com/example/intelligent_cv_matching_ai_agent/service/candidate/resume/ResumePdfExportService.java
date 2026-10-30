package com.example.intelligent_cv_matching_ai_agent.service.candidate.resume;

import com.example.intelligent_cv_matching_ai_agent.dto.response.candidate.resume.ResumeDownloadResponse;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.resume.ResumePdfExportRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.resume.ResumeContent;
import com.example.intelligent_cv_matching_ai_agent.util.shared.FlowSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.object;

@Service
public class ResumePdfExportService {

    private static final String RENDERER_VERSION = "cv-pdf-v1";

    private final ResumePdfExportRepository db;

    private final CurrentUser current;

    private final ResumePdfRenderer renderer;

    private final ObjectMapper mapper;

    private final Path storage;

    private final TransactionTemplate transactions;

    public ResumePdfExportService(
        ResumePdfExportRepository db,
        CurrentUser current,
        ResumePdfRenderer renderer,
        ObjectMapper mapper,
        PlatformTransactionManager manager,
        @Value("${app.storage.path:./storage/resumes}")
        String storage) {
        this.db = db;
        this.current = current;
        this.renderer = renderer;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(manager);
        this.storage = Path.of(storage).toAbsolutePath().normalize();
    }

    public Map<String, Object> export(
        UUID id) {
        Map<String, Object> resume = requireReady(db.resume(id));
        String template = template(resume);
        String fingerprint = fingerprint(resume, template);
        if (fingerprint.equals(resume.get("pdf_input_hash")) && existingPdf(resume)) {
            return response(resume, true);
        }
        // Rendering holds no database lock. Publication checks the exact inputs again.
        byte[] bytes = renderer.render(resume, template);
        String key = UUID.randomUUID() + ".pdf";
        Path output = safeFile(key);
        String filename = Objects.toString(resume.get("title"), "CV").replaceAll("[^\\p{L}\\p{N}_-]", "_");
        filename = filename.substring(0, Math.min(filename.length(), 180)) + ".pdf";
        try {
            Files.createDirectories(storage);
            Files.write(output, bytes, StandardOpenOption.CREATE_NEW);
            String downloadName = filename;
            Map<String, Object> saved = transactions.execute(status -> {
                Map<String, Object> latest = requireReady(db.lock(id));
                if (!fingerprint.equals(fingerprint(latest, template(latest)))) {
                    throw ApiException.conflict("CV or template changed during PDF export; confirm and retry");
                }
                if (db.save(id, key, downloadName, bytes.length, fingerprint) != 1) {
                    throw ApiException.conflict("CV is no longer ready for PDF export");
                }
                Map<String, Object> published = new LinkedHashMap<>(latest);
                published.put("file_url", key);
                published.put("file_name", downloadName);
                published.put("mime_type", "application/pdf");
                published.put("file_size_bytes", bytes.length);
                published.put("pdf_input_hash", fingerprint);
                return response(published, false);
            });
            return saved;
        } catch (IOException exception) {
            deleteFailedExport(output);
            throw new ApiException(503, "CV PDF storage is unavailable");
        } catch (RuntimeException exception) {
            deleteFailedExport(output);
            throw exception;
        }
    }

    public ResumeDownloadResponse download(
        UUID id) {
        Map<String, Object> exported = export(id);
        try {
            return new ResumeDownloadResponse(
                Files.readAllBytes(safeFile(exported.get("file_url").toString())),
                exported.get("file_name").toString(),
                "application/pdf"
            );
        } catch (IOException exception) {
            throw new ApiException(503, "Exported PDF is temporarily unavailable; retry export");
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
        return FlowSupport.hash(
            RENDERER_VERSION + "\n"
                + ResumeContent.contentHash(
                    mapper,
                    Objects.toString(resume.get("title"), ""),
                    Objects.toString(resume.get("language"), "vi"),
                    Objects.toString(resume.get("raw_content"), ""),
                    object(resume.get("structured_data"))
                ) + "\n" + template
        );
    }

    private boolean existingPdf(
        Map<String, Object> resume) {
        try {
            Path file = safeFile(Objects.toString(resume.get("file_url"), ""));
            return "application/pdf".equals(resume.get("mime_type")) && Files.isRegularFile(file)
                && Files.size(file) > 0 && Files.size(file) <= 20 * 1024 * 1024;
        } catch (IOException | ApiException exception) {
            return false;
        }
    }

    private Path safeFile(
        String key) {
        if (!key.matches("[0-9a-f-]{36}\\.pdf")) {
            throw ApiException.badRequest("Invalid PDF storage key");
        }
        Path file = storage.resolve(key).normalize();
        if (!file.getParent().equals(storage) || Files.isSymbolicLink(file)) {
            throw ApiException.badRequest("Invalid PDF storage path");
        }
        return file;
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

    private static void deleteFailedExport(
        Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Only this attempt's unpublished file is eligible for cleanup.
        }
    }
}
