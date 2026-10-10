package com.example.intelligent_cv_matching_ai_agent.service.candidate.resume;

import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ResumeFileExtractor;

import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ResumeData;

import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.resume.ResumeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;
import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ExtractedResume;
import com.example.intelligent_cv_matching_ai_agent.dto.response.candidate.resume.ResumeDownloadResponse;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

@Service
public class ResumeService {

    private final ResumeRepository db;

    private final CurrentUser current;

    private final AiService ai;

    private final ResumeFileExtractor extractor;

    private final ObjectMapper mapper;

    private final Path storage;

    public ResumeService(
        ResumeRepository db,
        CurrentUser current,
        AiService ai,
        ResumeFileExtractor extractor,
        ObjectMapper mapper,
        @Value("${app.storage.path:./storage/resumes}")
        String storage) {
        this.db = db;
        this.current = current;
        this.ai = ai;
        this.extractor = extractor;
        this.mapper = mapper;
        this.storage = Path.of(storage).toAbsolutePath().normalize();
    }

    public List<Map<String, Object>> templates() {
        return db.listResumeTemplatesByIsActive();
    }

    public List<Map<String, Object>> list() {
        return db.listResumesByUserId(current.id());
    }

    public Map<String, Object> owned(
        UUID id) {
        Map<String, Object> resume = db.getResumesById(id);
        current.owns(resume, "user_id");

        return resume;
    }

    public Map<String, Object> get(
        UUID id) {
        Map<String, Object> resume = new LinkedHashMap<>(owned(id));
        resume.put("experiences", db.listResumeExperiencesByResumeId(id));
        resume.put("educations", db.listResumeEducationsByResumeId(id));
        resume.put("projects", db.listResumeProjectsByResumeId(id));
        resume.put("certifications", db.listResumeCertificationsByResumeId(id));
        resume.put("skills", db.listResumeSkillsByResumeId(id));
        resume.put("parse_results", db.listResumeParseResultsByResumeId(id));

        return resume;
    }

    @Transactional
    public Map<String, Object> upload(
        MultipartFile file,
        String title,
        String language) {
        UUID user = current.id();
        String lang = language(language);
        String requestedTitle = title == null || title.isBlank() ? null : ResumeData.text(title, "title", 255);

        ExtractedResume extracted = extractor.extract(file);

        String effectiveTitle = requestedTitle == null ? extracted.filename() : requestedTitle;
        String extension = extracted.filename().substring(extracted.filename().lastIndexOf('.'));
        String fileKey = UUID.randomUUID() + extension.toLowerCase(Locale.ROOT);
        write(fileKey, extracted.bytes());
        Map<String, Object> structured = ResumeData.normalize(Map.of());
        String error = null;
        String parseStatus = "COMPLETED";

        if (ai.enabled() && !extracted.text().isBlank()) {
            try {
                structured = ResumeData
                    .normalize(ai.generate("CV_PARSE", Map.of("raw_text", extracted.text(), "language", lang)));
            } catch (ApiException ex) {
                error = ex.getMessage();
                parseStatus = "FAILED";
            }
        }

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("user_id", user);
        values.put("title", effectiveTitle);
        values.put("source", "UPLOAD");
        values.put("status", "REVIEW_REQUIRED");
        values.put("language", lang);
        values.put("file_name", extracted.filename());
        values.put("file_url", fileKey);
        values.put("mime_type", extracted.mimeType());
        values.put("file_size_bytes", extracted.bytes().length);
        values.put("raw_content", extracted.text());
        values.put("structured_data", db.json(structured));
        values.put("content_hash", contentHash(effectiveTitle, lang, extracted.text(), structured));

        UUID id = db.insertResumes(values);

        Map<String, Object> parse = new LinkedHashMap<>();
        parse.put("resume_id", id);
        parse.put("parser_name", ai.enabled() ? extracted.parser() + "_AI" : extracted.parser());
        parse.put("parser_version", "1");
        parse.put("ocr_used", extracted.ocrUsed());
        parse.put("raw_text", extracted.text());
        parse.put("extracted_data", db.json(structured));
        parse.put("normalized_data", db.json(structured));
        parse.put("status", parseStatus);
        parse.put(
            "error_message",
            error != null
                ? error
                : extracted.text().isBlank() ? "No text found; enable OCR or enter CV information manually" : null
        );

        db.insertResumeParseResults(parse);

        return get(id);
    }

    @Transactional
    public Map<String, Object> create(
        Map<String, Object> input,
        boolean generated) {
        current.id();
        String title = requiredTitle(input), lang = language(input.get("language"));
        String raw = Optional.ofNullable(input.get("raw_content")).map(v -> ResumeData.text(v, "raw_content", 200_000))
            .orElse("");
        UUID template = uuid(input.get("template_id"));

        if (template != null)
            db.getResumeTemplatesById(template);

        Map<String, Object> structured;

        if (generated) {
            Map<String, Object> safe = new LinkedHashMap<>(input);
            safe.put("career_profile", db.findCandidateProfilesByUserId(current.id()).orElse(Map.of()));
            structured = ResumeData.normalize(ai.generate("CV_BUILD", safe));
        } else
            structured = ResumeData.normalize(document(input));

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("user_id", current.id());
        values.put("template_id", template);
        values.put("title", title);
        values.put("source", generated ? "AI_BUILDER" : "MANUAL");
        values.put("status", generated ? "REVIEW_REQUIRED" : "DRAFT");
        values.put("language", lang);
        values.put("structured_data", db.json(structured));
        values.put("raw_content", raw);
        values.put("content_hash", contentHash(title, lang, raw, structured));

        return get(db.insertResumes(values));
    }

    @Transactional
    public Map<String, Object> update(
        UUID id,
        Map<String, Object> input) {
        Map<String, Object> before = owned(id);
        if ("ARCHIVED".equals(before.get("status").toString()))
            throw ApiException.conflict("Archived CV cannot be edited");

        Map<String, Object> data = input.containsKey("structured_data")
            ? ResumeData.normalize(document(input))
            : ResumeData.normalize(asMap(before.get("structured_data")));
        String raw = input.containsKey("raw_content")
            ? ResumeData.text(input.get("raw_content"), "raw_content", 200_000)
            : Objects.toString(before.get("raw_content"), "");
        String title = input.containsKey("title") ? requiredTitle(input) : before.get("title").toString();
        String lang = input.containsKey("language")
            ? language(input.get("language"))
            : Objects.toString(before.get("language"), "vi");
        String newHash = contentHash(title, lang, raw, data);
        boolean changed = !Objects.equals(before.get("content_hash"), newHash);

        db.updateResumesById(
            title,
            lang,
            raw,
            db.json(data),
            newHash,
            changed ? "REVIEW_REQUIRED" : before.get("status").toString(),
            changed,
            id
        );

        if (changed)
            invalidate(id);

        return get(id);
    }

    @Transactional
    public Map<String, Object> confirm(
        UUID id,
        Map<String, Object> input) {
        db.getUsersByIdForUpdate(current.id());
        Map<String, Object> before = owned(id);
        if ("ARCHIVED".equals(before.get("status").toString()))
            throw ApiException.conflict("Archived CV cannot be confirmed");

        Map<String, Object> data = ResumeData
            .normalize(input.containsKey("structured_data") ? document(input) : asMap(before.get("structured_data")));
        String raw = input.containsKey("raw_content")
            ? ResumeData.text(input.get("raw_content"), "raw_content", 200_000)
            : Objects.toString(before.get("raw_content"), "");
        boolean hasContent = !raw.isBlank() || !Objects.toString(data.get("summary"), "").isBlank()
            || List.of("experience", "education", "projects", "certifications", "skills").stream()
                .anyMatch(k -> !((List<?>) data.get(k)).isEmpty());
        if (!hasContent)
            throw ApiException.badRequest("Review and complete CV content before confirmation");

        String newHash =
            contentHash(before.get("title").toString(), Objects.toString(before.get("language"), "vi"), raw, data);
        replaceSections(id, data);

        if (!Objects.equals(newHash, before.get("content_hash")))
            invalidate(id);

        boolean primary = Boolean.TRUE.equals(input.get("is_primary")) || Boolean.TRUE.equals(before.get("is_primary"));

        if (primary)
            db.updateResumesByUserId(current.id());

        db.updateResumesById2(db.json(data), raw, newHash, primary, id);

        return get(id);
    }

    @Transactional
    public Map<String, Object> primary(
        UUID id) {
        db.getUsersByIdForUpdate(current.id());
        Map<String, Object> resume = owned(id);
        if (!"READY".equals(resume.get("status").toString()))
            throw ApiException.conflict("Only a confirmed CV may be primary");

        db.updateResumesByUserId2(current.id());
        db.updateResumesById3(id);

        return get(id);
    }

    @Transactional
    public void archive(
        UUID id) {
        owned(id);

        db.updateResumesById4(id);
        invalidate(id);
    }

    @Transactional
    public Map<String, Object> analyze(
        UUID id) {
        Map<String, Object> resume = owned(id);
        if (!"READY".equals(resume.get("status").toString()))
            throw ApiException.conflict("Confirm the CV before analysis");

        Map<String, Object> result = ai.enabled()
            ? ai.generate("CV_ANALYZE", Map.of("resume", resume, "resume_text", canonicalText(resume)))
            : baseline(resume);
        String mode = ai.enabled() ? "AI_QUALITY" : "RULE_BASED";
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("resume_id", id);
        fields.put("analysis_type", mode);
        fields.put("overall_score", result.get("overall_score"));
        fields.put("ats_score", result.get("ats_score"));
        fields.put("summary", result.get("summary"));
        fields.put("strengths_json", db.json(result.getOrDefault("strengths", List.of())));
        fields.put("issues_json", db.json(result.getOrDefault("issues", List.of())));
        fields.put("model_name", ai.enabled() ? result.get("_model") : "rules-v1");

        UUID analysis = db.insertResumeAnalyses(fields);

        Object suggestions = result.get("suggestions");

        if (suggestions instanceof List<?> rows)
            for (Object value : rows)
                if (value instanceof Map<?, ?> row) {
                    Map<String, Object> suggestion = new LinkedHashMap<>();
                    suggestion.put("analysis_id", analysis);

                    for (String col : List
                        .of("category", "severity", "original_content", "suggested_content", "explanation"))
                        if (row.get(col) != null)
                            suggestion.put(col, row.get(col));

                    db.insertResumeAnalysisSuggestions(suggestion);
                }

        return analysis(analysis, id);
    }

    public List<Map<String, Object>> analyses(
        UUID id) {
        owned(id);

        return db.listResumeAnalysesByResumeId(id);
    }

    public Map<String, Object> analysis(
        UUID analysisId,
        UUID resumeId) {
        owned(resumeId);
        Map<String, Object> result = new LinkedHashMap<>(db.getResumeAnalysesById(analysisId, resumeId));
        result.put("suggestions", db.listResumeAnalysisSuggestionsByAnalysisId(analysisId));

        return result;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Map<String, Object> embedding(
        UUID id) {
        Map<String, Object> resume = owned(id);
        if (!"READY".equals(resume.get("status").toString()))
            throw ApiException.conflict("Confirm the CV before generating its embedding");
        if (!ai.embeddingsEnabled())
            throw new ApiException(503, "AI embedding provider is disabled");

        Object hash = resume.get("content_hash");
        Optional<Map<String, Object>> cached =
            db.findResumeEmbeddingsByResumeId(id, ai.provider(), ai.embeddingModel(), hash);
        if (cached.isPresent())
            return cached.get();

        UUID task = db.insertVectorIndexingJobs(
            Map.of(
                "entity_type",
                "RESUME",
                "entity_id",
                id,
                "operation",
                "EMBED",
                "provider",
                ai.provider(),
                "model",
                ai.embeddingModel(),
                "status",
                "PROCESSING",
                "attempt_count",
                1,
                "started_at",
                java.time.Instant.now()
            )
        );

        float[] vector;

        try {
            vector = ai.embed(canonicalText(resume));
            Map<String, Object> latest = db.getResumesByIdForUpdate(id);
            if (!"READY".equals(latest.get("status")) || !Objects.equals(hash, latest.get("content_hash")))
                throw ApiException.conflict("CV changed during embedding; confirm the latest content and retry");
        } catch (ApiException ex) {
            db.updateVectorIndexingJobsById(ex.getMessage(), task);
            throw ex;
        }

        String literal = Arrays.toString(vector);

        db.updateResumeEmbeddingsByResumeId(id);

        UUID embedding = (UUID) db.readResumeEmbeddings(id, ai.provider(), ai.embeddingModel(), literal, hash);

        db.updateVectorIndexingJobsById2(task);

        return db.getResumeEmbeddingsById(embedding);
    }

    public String canonicalText(
        Map<String, Object> resume) {
        // Reviewed structured content is authoritative; raw extraction is only used
        // before review or when no structure exists.
        Map<String, Object> data = asMap(resume.get("structured_data"));
        boolean reviewed = !Objects.toString(data.get("summary"), "").isBlank()
            || List.of("experience", "education", "projects", "certifications", "skills").stream()
                .anyMatch(k -> data.get(k) instanceof List<?> rows && !rows.isEmpty());
        String text = reviewed ? mapper.writeValueAsString(data) : Objects.toString(resume.get("raw_content"), "");

        return Objects.toString(resume.get("title"), "") + "\n" + text;
    }

    public ResumeDownloadResponse download(
        UUID id,
        boolean text) {
        Map<String, Object> resume = owned(id);

        if (!text && resume.get("file_url") != null) {
            try {
                return new ResumeDownloadResponse(
                    Files.readAllBytes(safeFile(resume.get("file_url").toString())),
                    resume.get("file_name").toString(),
                    resume.get("mime_type").toString()
                );
            } catch (Exception ex) {
                throw ApiException.notFound("Stored CV file is unavailable");
            }
        }

        return new ResumeDownloadResponse(
            canonicalText(resume).getBytes(StandardCharsets.UTF_8),
            resume.get("title").toString().replaceAll("[^\\p{L}\\p{N}_-]", "_") + ".txt",
            "text/plain; charset=UTF-8"
        );
    }

    private void replaceSections(
        UUID resume,
        Map<String, Object> data) {
        for (String section : List.of("experience", "education", "projects", "certifications")) {
            db.deleteSection(section, resume);

            for (Object item : (List<?>) data.get(section)) {
                Map<String, Object> values = new LinkedHashMap<>(asMap(item));
                values.put("resume_id", resume);

                for (String col : new ArrayList<>(values.keySet())) {
                    if (col.endsWith("_date") && values.get(col) != null)
                        values.put(col, LocalDate.parse(values.get(col).toString()));
                    else if (col.equals("technologies"))
                        values.put(col, db.json(values.get(col)));
                }

                db.insertSection(section, values);
            }
        }

        db.deleteResumeSkillsByResumeId(resume);

        Set<UUID> seen = new HashSet<>();

        for (Object item : (List<?>) data.get("skills")) {
            Map<String, Object> row = asMap(item);
            UUID skill = uuid(row.get("skill_id"));

            if (skill != null)
                db.getSkillsById(skill);
            else {
                String name = row.get("name").toString();
                String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "-").replaceAll("^-|-$", "");
                if (slug.isBlank())
                    throw ApiException.badRequest("Invalid skill name");

                skill = (UUID) db.readSkills(name, slug);
            }

            if (seen.add(skill))
                db.updateResumeSkills(
                    resume,
                    skill,
                    row.get("proficiency_level"),
                    row.get("years_experience"),
                    "REVIEWED"
                );
        }
    }

    private void invalidate(
        UUID id) {
        db.updateResumeEmbeddingsByResumeId(id);
        db.updateRoadmapsByGapAnalysisId(id);
    }

    private Map<String, Object> baseline(
        Map<String, Object> resume) {
        Map<String, Object> data = asMap(resume.get("structured_data"));
        List<String> strengths = new ArrayList<>(), issues = new ArrayList<>();
        List<Map<String, Object>> suggestions = new ArrayList<>();
        int score = 0;

        for (String key : List.of("summary", "experience", "education", "skills", "projects")) {
            Object value = data.get(key);
            boolean present =
                value instanceof List<?> list ? !list.isEmpty() : value instanceof String s && !s.isBlank();

            if (present) {
                score += 20;
                strengths.add("Có mục " + key);
            } else {
                issues.add("Thiếu mục " + key);
                suggestions.add(
                    Map.of(
                        "category",
                        key,
                        "severity",
                        "MEDIUM",
                        "explanation",
                        "Bổ sung thông tin thực tế cho mục " + key
                    )
                );
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("overall_score", score);
        result.put("ats_score", null);
        result.put(
            "summary",
            ("Kiểm tra mức độ đầy đủ của các mục CV bằng quy tắc. Chưa đánh giá ATS,"
                + " chất lượng nội dung hoặc độ phù hợp với vị trí tuyển dụng.")
        );
        result.put("strengths", strengths);
        result.put("issues", issues);
        result.put("suggestions", suggestions);

        return result;
    }

    private void write(
        String key,
        byte[] bytes) {
        try {
            Files.createDirectories(storage);
            Path file = safeFile(key);
            Files.write(file, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

            if (TransactionSynchronizationManager.isSynchronizationActive())
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

                    @Override
                    public void afterCompletion(
                        int status) {
                        if (status == STATUS_ROLLED_BACK)
                            try {
                                Files.deleteIfExists(file);
                            } catch (java.io.IOException ignored) {
                            }
                    }
                });
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException(503, "CV storage is unavailable");
        }
    }

    private Path safeFile(
        String key)
        throws java.io.IOException {
        if (!key.matches("[0-9a-f-]{36}\\.(pdf|docx|txt|png|jpg|jpeg)"))
            throw ApiException.badRequest("Invalid storage key");

        Path root = storage.toRealPath();
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root) || Files.isSymbolicLink(target))
            throw ApiException.forbidden("Invalid file path");

        return target;
    }

    private String hash(
        String value) {
        try {
            return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String contentHash(
        String title,
        String language,
        String raw,
        Map<String, Object> data) {
        return hash(mapper.writeValueAsString(List.of(title, language, raw, stable(data))));
    }

    private Object stable(
        Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach(
                (
                    key,
                    item) -> sorted.put(key.toString(), stable(item))
            );

            return sorted;
        }

        if (value instanceof List<?> list)
            return list.stream().map(this::stable).toList();

        return value;
    }

    private UUID uuid(
        Object value) {
        if (value == null)
            return null;

        try {
            return UUID.fromString(value.toString());
        } catch (Exception ex) {
            throw ApiException.badRequest("Invalid UUID");
        }
    }

    private String requiredTitle(
        Map<String, Object> input) {
        String title = ResumeData.text(input.get("title"), "title", 255);
        if (title.isBlank())
            throw ApiException.badRequest("title is required");

        return title;
    }

    private String language(
        Object value) {
        String lang = value == null ? "vi" : value.toString();
        if (!Set.of("vi", "en", "VI", "EN").contains(lang))
            throw ApiException.badRequest("language must be vi or en");

        return lang.toLowerCase(Locale.ROOT);
    }

    private Map<String, Object> document(
        Map<String, Object> input) {
        if (!(input.get("structured_data") instanceof Map<?, ?>))
            throw ApiException.badRequest("structured_data must be an object");

        return asMap(input.get("structured_data"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(
        Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }
}
