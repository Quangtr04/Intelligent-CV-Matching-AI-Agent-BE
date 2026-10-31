package com.example.intelligent_cv_matching_ai_agent.service.candidate.resume;

import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.AtsScoring;
import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ResumeFileExtractor;

import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ResumeData;

import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiResponseCache;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.resume.ResumeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
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
import java.time.LocalDate;
import java.time.Duration;
import java.util.*;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.resume.ResumeContent;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.resume.ResumeContentQuality;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.resume.ResumeInput;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.resume.ResumeWritingQuality;
import com.example.intelligent_cv_matching_ai_agent.util.shared.FlowSupport;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.object;

@Service
public class ResumeService {

    private final ResumeRepository db;

    private final CurrentUser current;

    private final AiService ai;

    private final ResumeFileExtractor extractor;

    private final ObjectMapper mapper;

    private final Path storage;

    private final AiResponseCache responseCache;

    public ResumeService(
        ResumeRepository db,
        CurrentUser current,
        AiService ai,
        ResumeFileExtractor extractor,
        ObjectMapper mapper,
        @Value("${app.storage.path:./storage/resumes}")
        String storage) {
        this(db, current, ai, extractor, mapper, storage, null);
    }

    @Autowired
    public ResumeService(
        ResumeRepository db,
        CurrentUser current,
        AiService ai,
        ResumeFileExtractor extractor,
        ObjectMapper mapper,
        @Value("${app.storage.path:./storage/resumes}")
        String storage,
        ObjectProvider<StringRedisTemplate> redis) {
        this.db = db;
        this.current = current;
        this.ai = ai;
        this.extractor = extractor;
        this.mapper = mapper;
        this.storage = Path.of(storage).toAbsolutePath().normalize();
        this.responseCache = new AiResponseCache(redis);
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
        UUID template = ResumeInput.optionalUuid(resume.get("template_id"));
        resume.put("template", template == null ? null : db.findResumeTemplateById(template).orElse(null));
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
        String lang = ResumeInput.language(language);
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
        values
            .put("content_hash", ResumeContent.contentHash(mapper, effectiveTitle, lang, extracted.text(), structured));

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
        String title = ResumeInput.requiredTitle(input), lang = ResumeInput.language(input.get("language"));
        String raw = Optional.ofNullable(input.get("raw_content")).map(v -> ResumeData.text(v, "raw_content", 200_000))
            .orElse("");
        UUID template = ResumeInput.optionalUuid(input.get("template_id"));

        if (template != null)
            db.getResumeTemplatesById(template);

        Map<String, Object> structured;

        if (generated) {
            Map<String, Object> safe = new LinkedHashMap<>(input);
            safe.put("career_profile", db.findCandidateProfilesByUserId(current.id()).orElse(Map.of()));
            structured = ResumeData.normalize(ai.generate("CV_BUILD", safe));
        } else
            structured = ResumeData.normalize(ResumeInput.document(input));

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("user_id", current.id());
        values.put("template_id", template);
        values.put("title", title);
        values.put("source", generated ? "AI_BUILDER" : "MANUAL");
        values.put("status", generated ? "REVIEW_REQUIRED" : "DRAFT");
        values.put("language", lang);
        values.put("structured_data", db.json(structured));
        values.put("raw_content", raw);
        values.put("content_hash", ResumeContent.contentHash(mapper, title, lang, raw, structured));

        return get(db.insertResumes(values));
    }

    @Transactional
    public Map<String, Object> update(
        UUID id,
        Map<String, Object> input) {
        boolean templateChanged = input.containsKey("template_id");
        Map<String, Object> before = templateChanged ? db.getResumesByIdForUpdate(id) : owned(id);
        if (templateChanged)
            current.owns(before, "user_id");
        if ("ARCHIVED".equals(before.get("status").toString()))
            throw ApiException.conflict("Archived CV cannot be edited");

        UUID template = templateChanged ? ResumeInput.optionalUuid(input.get("template_id")) : null;
        if (template != null)
            db.getResumeTemplatesById(template);

        boolean contentProvided = input.containsKey("title") || input.containsKey("language")
            || input.containsKey("raw_content") || input.containsKey("structured_data");
        if (templateChanged && !contentProvided) {
            db.updateResumeTemplateById(template, id);
            return get(id);
        }

        Map<String, Object> data = input.containsKey("structured_data")
            ? ResumeData.normalize(ResumeInput.document(input))
            : ResumeData.normalize(object(before.get("structured_data")));
        String raw = input.containsKey("raw_content")
            ? ResumeData.text(input.get("raw_content"), "raw_content", 200_000)
            : Objects.toString(before.get("raw_content"), "");
        String title = input.containsKey("title") ? ResumeInput.requiredTitle(input) : before.get("title").toString();
        String lang = input.containsKey("language")
            ? ResumeInput.language(input.get("language"))
            : Objects.toString(before.get("language"), "vi");
        String newHash = ResumeContent.contentHash(mapper, title, lang, raw, data);
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

        if (templateChanged)
            db.updateResumeTemplateById(template, id);

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

        Map<String, Object> data = ResumeData.normalize(
            input.containsKey("structured_data") ? ResumeInput.document(input) : object(before.get("structured_data"))
        );
        String raw = input.containsKey("raw_content")
            ? ResumeData.text(input.get("raw_content"), "raw_content", 200_000)
            : Objects.toString(before.get("raw_content"), "");
        boolean hasContent = !raw.isBlank() || !Objects.toString(data.get("summary"), "").isBlank()
            || List.of("experience", "education", "projects", "certifications", "skills").stream()
                .anyMatch(k -> !((List<?>) data.get(k)).isEmpty());
        if (!hasContent)
            throw ApiException.badRequest("Review and complete CV content before confirmation");

        String newHash = ResumeContent.contentHash(
            mapper,
            before.get("title").toString(),
            Objects.toString(before.get("language"), "vi"),
            raw,
            data
        );
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

        boolean useAi = ai.enabled();
        String mode = useAi ? "AI_QUALITY" : "RULE_BASED";
        Map<String, Object> input = Map.of(
            "resume",
            ResumeWritingQuality.source(resume),
            "resume_text",
            canonicalText(resume),
            "language",
            Objects.toString(resume.get("language"), "vi")
        );
        String inputHash = FlowSupport.hash(
            mapper.writeValueAsString(
                List.of(
                    mode,
                    useAi ? Objects.toString(ai.provider(), "") : "rules",
                    useAi ? Objects.toString(ai.model(), "") : "rules-v1",
                    useAi ? Objects.toString(ai.cacheFingerprint("CV_ANALYZE"), "") : "",
                    ResumeWritingQuality.RUBRIC_VERSION,
                    ResumeContentQuality.RUBRIC_VERSION,
                    AtsScoring.RUBRIC_VERSION,
                    Objects.toString(resume.get("raw_content"), ""),
                    Objects.toString(resume.get("mime_type"), ""),
                    input
                )
            )
        );
        Map<String, String> cacheIdentity =
            Map.of("user_id", current.id().toString(), "resume_id", id.toString(), "input_hash", inputHash);
        String cacheKey = "edunexus:resume-analysis:response:v1:" + current.id() + ":" + id + ":" + inputHash;
        Optional<Map<String, Object>> cached =
            responseCache.read(cacheKey, cacheIdentity).filter(row -> validCachedAnalysis(row, id, mode));
        if (cached.isPresent()) {
            Map<String, Object> response = new LinkedHashMap<>(cached.get());
            response.put("cached", true);
            return response;
        }

        Optional<Map<String, Object>> saved = db.findResumeAnalysisByInput(id, mode, inputHash, current.id());
        if (saved.isPresent()) {
            UUID savedId = ResumeInput.optionalUuid(saved.get().get("id"));
            Map<String, Object> response = savedId == null ? Map.of() : analysis(savedId, id);
            if (validCachedAnalysis(response, id, mode)) {
                response.put("cached", true);
                responseCache.putAfterCommit(cacheKey, cacheIdentity, response, Duration.ofHours(24));
                return response;
            }
        }

        Map<String, Object> result;
        if (useAi) {
            Map<String, Object> generated = ai.generate("CV_ANALYZE", input);
            Map<String, Object> contentResult = ResumeContentQuality.normalize(generated, resume);

            Map<String, Object> merged = new LinkedHashMap<>(generated);

            merged.put("content_score", contentResult.get("score"));

            result = ResumeWritingQuality.normalize(merged, resume);

            result.put("content_quality", contentResult);

        } else {
            result = ResumeContent.baseline(resume);
        }
        // The ATS score is always rule-based so the same CV keeps the same score; model output never sets it.
        Map<String, Object> atsReport = AtsScoring.score(resume);
        result.put("ats_score", atsReport.get("score"));
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("resume_id", id);
        fields.put("analysis_type", mode);
        fields.put("input_hash", inputHash);
        fields.put("overall_score", result.get("overall_score"));
        fields.put("ats_score", result.get("ats_score"));
        fields.put("summary", result.get("summary"));
        fields.put("ats_report_json", db.json(atsReport));
        fields.put("strengths_json", db.json(result.getOrDefault("strengths", List.of())));
        fields.put("issues_json", db.json(result.getOrDefault("issues", List.of())));
        if (useAi) {
            fields.put("writing_quality_json", db.json(result.get("writing_quality")));
            fields.put("content_quality_json", db.json(result.get("content_quality")));
        } ;
        fields.put("model_name", useAi ? result.get("_model") : "rules-v1");

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

        Map<String, Object> response = analysis(analysis, id);
        response.put("cached", false);
        responseCache.putAfterCommit(cacheKey, cacheIdentity, response, Duration.ofHours(24));
        return response;
    }

    private boolean validCachedAnalysis(
        Map<String, Object> response,
        UUID resumeId,
        String mode) {
        if (!resumeId.toString().equals(Objects.toString(response.get("resume_id"), ""))
            || !mode.equals(response.get("analysis_type")) || !(response.get("suggestions") instanceof List<?>)
            || !(response.get("overall_score") instanceof Number))
            return false;
        try {
            UUID.fromString(Objects.toString(response.get("id"), ""));
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public List<Map<String, Object>> analyses(
        UUID id) {
        owned(id);

        return db.listResumeAnalysesByResumeId(id).stream().map(ResumeWritingQuality::expose).toList();
    }

    public Map<String, Object> analysis(
        UUID analysisId,
        UUID resumeId) {
        owned(resumeId);
        Map<String, Object> result = ResumeWritingQuality.expose(db.getResumeAnalysesById(analysisId, resumeId));
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
        return ResumeContent.canonicalText(mapper, resume);
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
            List<String> columns = switch (section) {
                case "experience" ->
                    List.of("company_name", "job_title", "start_date", "end_date", "is_current", "description");
                case "education" -> List.of("school_name", "degree", "major", "start_date", "end_date", "description");
                case "projects" -> List
                    .of("project_name", "role", "description", "technologies", "project_url", "start_date", "end_date");
                case "certifications" -> List.of("name", "issuer", "issue_date", "expiry_date", "credential_url");
                default -> throw new IllegalArgumentException("Unsupported CV section: " + section);
            };

            for (Object item : (List<?>) data.get(section)) {
                Map<String, Object> source = object(item);
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("resume_id", resume);

                // Editor IDs and presentation-only fields remain in structured_data,
                // while relational rows receive generated UUIDs and real table columns.
                for (String col : columns) {
                    Object value = source.get(col);
                    if (value == null)
                        continue;
                    if (col.endsWith("_date"))
                        values.put(col, LocalDate.parse(value.toString()));
                    else if (col.equals("technologies"))
                        values.put(col, db.json(value));
                    else
                        values.put(col, value);
                }

                db.insertSection(section, values);
            }
        }

        db.deleteResumeSkillsByResumeId(resume);

        Set<UUID> seen = new HashSet<>();

        for (Object item : (List<?>) data.get("skills")) {
            Map<String, Object> row = object(item);
            UUID skill = ResumeInput.optionalUuid(row.get("skill_id"));

            if (skill != null)
                db.getSkillsById(skill);
            else {
                String name = row.get("name").toString();
                String slug = ResumeInput.skillSlug(name);

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
}
