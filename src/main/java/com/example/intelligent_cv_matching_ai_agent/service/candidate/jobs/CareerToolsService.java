package com.example.intelligent_cv_matching_ai_agent.service.candidate.jobs;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.jobs.ApplicationDraftCreateRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.jobs.ApplicationDraftReviewRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.jobs.ApplicationDraftUpdateRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.jobs.CvRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.jobs.DraftRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.jobs.MatchRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.jobs.MatchingFeedbackRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.jobs.SalaryEstimateRequest;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobSourceValidator;

import com.example.intelligent_cv_matching_ai_agent.util.shared.FlowSupport;

import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;

import com.example.intelligent_cv_matching_ai_agent.repository.candidate.jobs.CareerToolsRepository;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;

import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiResponseCache;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AtsEvaluation;
import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.AtsScoring;

import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;
import java.util.*;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.jobs.CareerToolsSupport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import java.time.Duration;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

@Service
public class CareerToolsService {

    private static final JsonMapper CACHE_JSON = JsonMapper.builder().build();

    private final CareerToolsRepository db;

    private final CurrentUser user;

    private final MatchingService matching;

    private final ResumeService resumes;

    private final AiService ai;

    private final AiResponseCache responseCache;

    public CareerToolsService(
        CareerToolsRepository db,
        CurrentUser user,
        MatchingService matching,
        ResumeService resumes,
        AiService ai,
        ObjectProvider<StringRedisTemplate> redis) {
        this.db = db;
        this.user = user;
        this.matching = matching;
        this.resumes = resumes;
        this.ai = ai;
        this.responseCache = new AiResponseCache(redis);
    }

    public Map<String, Object> match(
        MatchRequest request) {
        return matching.match(request.basis(), request.resumeIds(), request.limit());
    }

    public Map<String, Object> run(
        UUID id) {
        return matching.result(id);
    }

    public List<Map<String, Object>> history(
        int limit) {
        return db.listJobMatchingRunsByUserId(user.id(), pageSize(limit));
    }

    public List<Map<String, Object>> aiUsage(
        int limit,
        int offset) {
        user.requireRole("CANDIDATE");

        return db.listAiUsageLogsByUserId(user.id(), pageSize(limit), offset(offset));
    }

    @Transactional
    public Map<String, Object> feedback(
        UUID id,
        MatchingFeedbackRequest feedback) {
        db.getJobMatchResultsById(id, user.id());

        db.updateJobMatchFeedback(user.id(), id, feedback.feedbackType(), feedback.rating(), feedback.comment());

        return db.getJobMatchFeedbackByUserId(user.id(), id);
    }

    @Transactional
    public Map<String, Object> ats(
        UUID jobId,
        CvRequest request) {
        user.requireRole("CANDIDATE");

        var cv = confirmed(request.resumeId());
        var job = activeJob(jobId);
        var skills = db.listMarketJobSkillsByMarketJobId(jobId);
        String language = CareerToolsSupport.languageOf(cv.get("language"));
        String resumeText = resumes.canonicalText(cv);
        Map<String, Object> input = fields(
            "resume",
            cv.get("structured_data"),
            "resume_text",
            resumeText,
            "resume_source",
            fields("source", cv.get("source"), "mime_type", cv.get("mime_type"), "file_name", cv.get("file_name")),
            "raw_content",
            cv.get("raw_content"),
            "job",
            job,
            "required_skills",
            skills,
            "language",
            language
        );
        boolean useAi = ai.enabled();
        String inputHash = FlowSupport.hash(
            "job-ats-response-v2\n" + AtsScoring.RUBRIC_VERSION + "\n" + CACHE_JSON.writeValueAsString(input) + "\n"
                + (useAi ? ai.provider() + "\n" + ai.model() + "\n" + text(ai.cacheFingerprint("JOB_ATS")) : "rules")
        );
        String cacheKey =
            "edunexus:ats:response:v1:" + user.id() + ":" + request.resumeId() + ":" + jobId + ":" + inputHash;
        Map<String, String> identity = Map.of(
            "user_id",
            user.id().toString(),
            "resume_id",
            request.resumeId().toString(),
            "job_id",
            jobId.toString(),
            "input_hash",
            inputHash
        );
        var redisHit = responseCache.read(cacheKey, identity)
            .filter(response -> validAtsResponse(response, request.resumeId(), jobId));
        if (redisHit.isPresent()) {
            redisHit.get().put("cached", true);
            return redisHit.get();
        }
        var cached = db.findJobResumeAnalysesByUserId(user.id(), request.resumeId(), jobId, inputHash);
        if (cached.isPresent()) {
            Map<String, Object> response = CareerToolsSupport.analysisResponse(cached.get(), true);
            responseCache.putAfterCommit(cacheKey, identity, response, Duration.ofHours(24));
            return response;
        }

        Map<String, Object> evaluation = AtsEvaluation.evaluate(ai, cv, resumeText, job, skills, language);

        UUID id = db.insertJobResumeAnalyses(
            fields(
                "user_id",
                user.id(),
                "resume_id",
                request.resumeId(),
                "market_job_id",
                jobId,
                "ats_score",
                evaluation.get("ats_score"),
                "strengths_json",
                db.json(evaluation.get("strengths")),
                "missing_skills_json",
                db.json(evaluation.get("missing_skills")),
                "improvement_suggestions_json",
                db.json(
                    fields(
                        "input_hash",
                        inputHash,
                        "items",
                        evaluation.get("improvement_suggestions"),
                        "ats_report",
                        evaluation.get("ats_report")
                    )
                ),
                "model_name",
                evaluation.get("model_name")
            )
        );

        Map<String, Object> response = CareerToolsSupport.analysisResponse(db.getJobResumeAnalysesById(id), false);
        responseCache.putAfterCommit(cacheKey, identity, response, Duration.ofHours(24));
        return response;
    }

    private boolean validAtsResponse(
        Map<String, Object> response,
        UUID resumeId,
        UUID jobId) {
        double score = number(response.get("ats_score"), Double.NaN);
        try {
            UUID.fromString(text(response.get("id")));
            return user.id().toString().equals(text(response.get("user_id")))
                && resumeId.toString().equals(text(response.get("resume_id")))
                && jobId.toString().equals(text(response.get("market_job_id"))) && Double.isFinite(score) && score >= 0
                && score <= 100 && response.get("improvement_suggestions_json") instanceof List<?>;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public Map<String, Object> targetedResume(
        UUID jobId,
        Map<String, Object> input) {
        user.requireRole("CANDIDATE");

        var job = activeJob(jobId);
        Map<String, Object> request = new LinkedHashMap<>(input);
        request.putIfAbsent("title", "CV - " + text(job.get("job_title")));
        request.put("target_job", job);
        request.put("job_requirements", db.listMarketJobSkillsByMarketJobId(jobId));
        request.putIfAbsent("language", CareerToolsSupport.languageOf(input.get("language")));
        request.put(
            "accuracy_requirement",
            ("Tailor wording to this job using only supplied candidate facts; leave "
                + "missing experience or qualifications for the candidate to complete.")
        );

        return resumes.create(request, true);
    }

    @Transactional
    public List<Map<String, Object>> createDraft(
        UUID jobId,
        DraftRequest request) {
        user.requireRole("CANDIDATE");

        var cv = confirmed(request.resumeId());
        var job = activeJob(jobId);
        var generated = ai.generate(
            "APPLICATION_EMAIL",
            fields(
                "resume",
                cv.get("structured_data"),
                "resume_text",
                resumes.canonicalText(cv),
                "job",
                job,
                "language",
                request.language() == null ? "vi" : request.language()
            )
        );
        String email = CareerToolsSupport.requiredAi(generated, "email"),
            letter = CareerToolsSupport.requiredAi(generated, "cover_letter");
        String subject = text(generated.get("subject"));

        UUID emailId = db.insertApplicationDrafts(
            fields(
                "user_id",
                user.id(),
                "market_job_id",
                jobId,
                "resume_id",
                request.resumeId(),
                "draft_type",
                "APPLICATION_EMAIL",
                "content",
                subject + "\n\n" + email,
                "model_used",
                ai.model()
            )
        );
        UUID letterId = db.insertApplicationDrafts(
            fields(
                "user_id",
                user.id(),
                "market_job_id",
                jobId,
                "resume_id",
                request.resumeId(),
                "draft_type",
                "COVER_LETTER",
                "content",
                letter,
                "model_used",
                ai.model()
            )
        );

        return db.listApplicationDraftsById(emailId, letterId);
    }

    public List<Map<String, Object>> drafts(
        UUID jobId) {
        return jobId == null
            ? db.listApplicationDraftsByUserId(user.id())
            : db.listApplicationDraftsByUserId2(user.id(), jobId);
    }

    public Map<String, Object> editDraft(
        UUID id,
        ApplicationDraftUpdateRequest input) {
        db.getApplicationDraftsById(id, user.id());

        db.updateApplicationDraftsById(input.content(), id);

        return db.getApplicationDraftsById2(id);
    }

    /** Saves a letter or email the candidate wrote themselves, without calling the AI. */
    @Transactional
    public Map<String, Object> createManualDraft(
        ApplicationDraftCreateRequest request) {
        user.requireRole("CANDIDATE");

        activeJob(request.jobId());
        Map<String, Object> row = fields(
            "user_id",
            user.id(),
            "market_job_id",
            request.jobId(),
            "draft_type",
            request.draftType(),
            "content",
            request.content()
        );
        if (request.resumeId() != null) {
            resumes.owned(request.resumeId());
            row.put("resume_id", request.resumeId());
        }

        return db.getApplicationDraftsById2(db.insertApplicationDrafts(row));
    }

    public void deleteDraft(
        UUID id) {
        db.getApplicationDraftsById(id, user.id());
        db.deleteApplicationDraftsById(id, user.id());
    }

    /**
     * Checks a letter the candidate wrote against the job (and CV, when given) and returns scores and
     * concrete fixes. Nothing is stored; the overall score is the mean of the five criterion scores.
     */
    public Map<String, Object> reviewDraft(
        ApplicationDraftReviewRequest request) {
        user.requireRole("CANDIDATE");

        var job = activeJob(request.jobId());
        String subject = request.subject() == null ? "" : request.subject().trim();
        String content = request.content().trim();
        Map<String, Object> input = fields(
            "document_type",
            request.draftType(),
            "subject",
            subject,
            "content",
            content,
            "job",
            job,
            "job_requirements",
            db.listMarketJobSkillsByMarketJobId(request.jobId()),
            "language",
            request.language() == null ? "vi" : request.language()
        );
        if (request.resumeId() != null) {
            var cv = resumes.owned(request.resumeId());
            input.put("resume", cv.get("structured_data"));
            input.put("resume_text", resumes.canonicalText(cv));
        } else {
            input.put("resume", "Not supplied: do not judge facts against a CV.");
        }

        var result = ai.generate("APPLICATION_REVIEW", input);

        Map<String, Object> scores = new LinkedHashMap<>();
        double total = 0;
        if (result.get("scores") instanceof Map<?, ?> raw)
            for (var entry : raw.entrySet()) {
                if (!(entry.getValue() instanceof Number number))
                    continue;
                int value = (int) Math.round(Math.max(0, Math.min(100, number.doubleValue())));
                scores.put(String.valueOf(entry.getKey()), value);
                total += value;
            }

        List<Map<String, Object>> issues = new ArrayList<>();
        if (result.get("issues") instanceof List<?> list)
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> issue))
                    continue;
                String original = text(issue.get("original")).trim();
                String suggestion = text(issue.get("suggestion")).trim();
                if (original.isEmpty() && suggestion.isEmpty() && text(issue.get("explanation")).isBlank())
                    continue;
                String location = original.isEmpty()
                    ? "MISSING"
                    : content.contains(original) ? "CONTENT" : subject.contains(original) ? "SUBJECT" : "UNKNOWN";
                issues.add(
                    fields(
                        "category",
                        text(issue.get("category")),
                        "severity",
                        text(issue.get("severity")),
                        "original",
                        original,
                        "suggestion",
                        suggestion,
                        "explanation",
                        text(issue.get("explanation")).trim(),
                        "location",
                        location
                    )
                );
            }

        int words = content.isEmpty() ? 0 : content.split("\\s+").length;
        return fields(
            "overall_score",
            scores.isEmpty() ? 0 : (int) Math.round(total / scores.size()),
            "scores",
            scores,
            "summary",
            text(result.get("summary")),
            "strengths",
            result.get("strengths"),
            "issues",
            issues,
            "missing_keywords",
            result.get("missing_keywords"),
            "word_count",
            words,
            "model_used",
            ai.model()
        );
    }

    public Map<String, Object> apply(
        UUID id,
        CvRequest request) {
        user.requireRole("CANDIDATE");

        var job = activeJob(id);
        String url = text(job.get("source_url"));
        if (url.isBlank())
            throw ApiException.conflict("No active, unexpired source is available for this job");
        JobSourceValidator.validateExternalUrl(url);

        if (request != null && request.resumeId() != null)
            confirmed(request.resumeId());

        UUID event = db.insertExternalApplyEvents(
            fields(
                "user_id",
                user.id(),
                "market_job_id",
                id,
                "resume_id",
                request == null ? null : request.resumeId(),
                "event_type",
                "REDIRECT",
                "target_url",
                url,
                "occurred_at",
                java.time.OffsetDateTime.now()
            )
        );

        return Map.of("event_id", event, "target_url", url);
    }

    public Map<String, Object> salary(
        UUID id) {
        user.requireRole("CANDIDATE");

        var profile = db.getCandidateProfilesByUserId(user.id());
        var job = activeJob(id);
        var generated = ai.generate(
            "SALARY_ESTIMATE",
            fields("profile", profile, "job", job, "language", CareerToolsSupport.languageOf(null))
        );
        double min = FlowSupport.score(generated.get("estimated_min"), 999999999999d, "minimum salary"),
            max = FlowSupport.score(generated.get("estimated_max"), 999999999999d, "maximum salary");
        CareerToolsSupport.validateSalaryInterval(min, max);
        String currency = CareerToolsSupport.salaryCurrency(generated.get("currency"));

        UUID estimate = db.insertSalaryEstimations(
            fields(
                "user_id",
                user.id(),
                "market_job_id",
                id,
                "target_job_title",
                job.get("job_title"),
                "estimated_min",
                min,
                "estimated_max",
                max,
                "currency",
                currency,
                "reasoning",
                db.json(generated.get("reasoning")),
                "model_name",
                ai.model()
            )
        );

        return db.getSalaryEstimationsById(estimate);
    }

    public Map<String, Object> salaryForCareer(
        SalaryEstimateRequest request) {
        user.requireRole("CANDIDATE");

        var profile = db.getCandidateProfilesByUserId(user.id());
        Map<String, Object> target = fields(
            "target_job_title",
            request.targetJobTitle(),
            "location",
            request.location(),
            "currency",
            request.currency()
        );
        var generated = ai.generate(
            "SALARY_ESTIMATE",
            fields(
                "profile",
                profile,
                "career_position",
                target,
                "language",
                CareerToolsSupport.languageOf(request.language())
            )
        );
        double min = FlowSupport.score(generated.get("estimated_min"), 999999999999d, "minimum salary"),
            max = FlowSupport.score(generated.get("estimated_max"), 999999999999d, "maximum salary");
        CareerToolsSupport.validateSalaryInterval(min, max);
        String currency = CareerToolsSupport.salaryCurrency(generated.get("currency"));

        UUID estimate = db.insertSalaryEstimations(
            fields(
                "user_id",
                user.id(),
                "target_job_title",
                request.targetJobTitle(),
                "estimated_min",
                min,
                "estimated_max",
                max,
                "currency",
                currency,
                "reasoning",
                db.json(generated.get("reasoning")),
                "model_name",
                ai.model()
            )
        );

        return db.getSalaryEstimationsById(estimate);
    }

    private Map<String, Object> confirmed(
        UUID id) {
        var cv = resumes.owned(id);
        if (!"READY".equals(cv.get("status")))
            throw ApiException.conflict("Confirm CV data first");

        return cv;
    }

    private Map<String, Object> activeJob(
        UUID id) {
        return db.getMarketJobPostsById(id);
    }
}
