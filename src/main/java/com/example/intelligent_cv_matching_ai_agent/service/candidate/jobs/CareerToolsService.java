package com.example.intelligent_cv_matching_ai_agent.service.candidate.jobs;

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

import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;
import java.util.*;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.jobs.CareerToolsSupport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

@Service
public class CareerToolsService {

    private final CareerToolsRepository db;

    private final CurrentUser user;

    private final MatchingService matching;

    private final ResumeService resumes;

    private final AiService ai;

    private final ObjectProvider<StringRedisTemplate> redis;

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
        this.redis = redis;
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
        String inputHash = FlowSupport.hash(
            resumes.canonicalText(cv) + "\n" + job.get("raw_content_hash") + "\n" + ai.provider() + "\n" + ai.model()
        );
        String cacheKey = "edunexus:ats:" + user.id() + ":" + request.resumeId() + ":" + jobId + ":" + inputHash;

        try {
            var client = redis.getIfAvailable();
            String cached = client == null ? null : client.opsForValue().get(cacheKey);

            if (cached != null) {
                var row = db.findJobResumeAnalysesById(
                    UUID.fromString(cached),
                    user.id(),
                    request.resumeId(),
                    jobId,
                    inputHash
                );
                if (row.isPresent())
                    return CareerToolsSupport.analysisResponse(row.get(), true);
            }
        } catch (org.springframework.data.redis.RedisConnectionFailureException | IllegalArgumentException ex) {

            /* Database cache remains available. */ }
        var cached = db.findJobResumeAnalysesByUserId(user.id(), request.resumeId(), jobId, inputHash);
        if (cached.isPresent())
            return CareerToolsSupport.analysisResponse(cached.get(), true);

        Map<String, Object> generated = ai.generate(
            "JOB_ATS",
            fields(
                "resume",
                cv.get("structured_data"),
                "resume_text",
                resumes.canonicalText(cv),
                "job",
                job,
                "language",
                CareerToolsSupport.languageOf(cv.get("language"))
            )
        );
        double score = FlowSupport.score(generated.get("ats_score"), 100, "ATS score");

        UUID id = db.insertJobResumeAnalyses(
            fields(
                "user_id",
                user.id(),
                "resume_id",
                request.resumeId(),
                "market_job_id",
                jobId,
                "ats_score",
                score,
                "strengths_json",
                db.json(array(generated.get("strengths"))),
                "missing_skills_json",
                db.json(array(generated.get("missing_skills"))),
                "improvement_suggestions_json",
                db.json(fields("input_hash", inputHash, "items", array(generated.get("improvement_suggestions")))),
                "model_name",
                ai.model()
            )
        );

        try {
            var client = redis.getIfAvailable();

            if (client != null)
                client.opsForValue().set(cacheKey, id.toString(), java.time.Duration.ofHours(24));
        } catch (org.springframework.data.redis.RedisConnectionFailureException ex) {
        }

        return CareerToolsSupport.analysisResponse(db.getJobResumeAnalysesById(id), false);
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
