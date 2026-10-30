package com.example.intelligent_cv_matching_ai_agent.service.guest.ai;

import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.guest.ai.GuestTrialRepository;
import com.example.intelligent_cv_matching_ai_agent.service.guest.identity.GuestAccess;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.jobs.MatchingService;
import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ResumeData;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.resume.ResumeInput;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;
import com.example.intelligent_cv_matching_ai_agent.dto.request.guest.ai.GuestBuildRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.guest.ai.GuestRecommendationRequest;
import java.time.Instant;
import java.util.*;

@Service
public class GuestTrialService {

    private final GuestTrialRepository db;

    private final GuestAccess guests;

    private final AiService ai;

    private final ResumeService resumes;

    public GuestTrialService(
        GuestTrialRepository db,
        GuestAccess guests,
        AiService ai,
        ResumeService resumes) {
        this.db = db;
        this.guests = guests;
        this.ai = ai;
        this.resumes = resumes;
    }

    public Object build(
        String key,
        GuestBuildRequest request) {
        UUID guest = guests.id(key);
        if (request.templateId() == null || !request.isValidSource()) {
            throw ApiException.badRequest("Choose a trial template and either demo_resume_id or structured_data");
        }
        UUID templateId = request.templateId();
        Map<String, Object> template = db.getResumeTemplatesById(templateId);
        boolean personal = request.demoResumeId() == null;
        String language =
            ResumeInput.language(request.language() == null ? template.get("language") : request.language());
        String raw = request.rawContent() == null ? "" : ResumeData.text(request.rawContent(), "raw_content", 200_000);
        Map<String, Object> facts = personal ? ResumeData.normalize(request.structuredData()) : null;
        if (personal && raw.isBlank() && facts.values().stream().noneMatch(this::hasContent)) {
            throw ApiException.badRequest("Enter CV information before building a personal trial CV");
        }
        boolean useAi = request.useAi() == null ? !personal : request.useAi();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("template", template);
        input.put("trial", true);
        input.put("language", language);
        if (personal) {
            input.put("structured_data", facts);
            input.put("raw_content", raw);
        } else {
            input.put("demo_resume", demo(request.demoResumeId()).get("structured_data"));
        }

        Map<String, Object> suggested =
            useAi ? ResumeData.normalize(ai.generateForGuest("CV_BUILD", input, guest)) : null;
        Map<String, Object> editable =
            personal ? facts : suggested != null ? suggested : ResumeData.normalize(document(input.get("demo_resume")));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("trial", true);
        response.put("mode", personal ? "PERSONAL" : "DEMO");
        response.put("template_id", templateId);
        response.put("language", language);
        response.put("status", useAi ? "REVIEW_REQUIRED" : "DRAFT");
        response.put("persisted", false);
        response.put("structured_data", editable);
        response.put("raw_content", raw);
        response.put("ai_generated", useAi);
        if (personal && suggested != null) {
            response.put("suggested_structured_data", suggested);
        }
        response.put("registration_prompt", "Đăng ký để tạo và lưu CV cá nhân.");
        return response;
    }

    private boolean hasContent(
        Object value) {
        if (value instanceof String text) {
            return !text.isBlank();
        }
        return value instanceof Collection<?> collection && !collection.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> document(
        Object value) {
        if (!(value instanceof Map<?, ?>)) {
            throw ApiException.badRequest("CV facts must be an object");
        }
        return (Map<String, Object>) value;
    }

    @Transactional
    public Object recommend(
        String key,
        GuestRecommendationRequest request) {
        UUID guest = guests.id(key);
        UUID resumeId = request.demoResumeId();
        Map<String, Object> demo = demo(resumeId);
        // This deterministic preview has a fixed, server-defined trial credit cost.
        UUID reservation = guests.reserve(guest, "JOB_RECOMMENDATION", 100, resumeId);

        try {
            List<Map<String, Object>> jobs = db.listMarketJobSkillsByMarketJobId();
            String text = resumes.canonicalText(demo);
            List<Map<String, Object>> ranked = new ArrayList<>();

            for (Map<String, Object> job : jobs) {
                Map<String, Object> entry = new LinkedHashMap<>(job);
                Map<String, Object> overlap = MatchingService.overlap(text, job);
                entry.put("comparison", overlap);
                entry.put("score", overlap.get("score"));
                ranked.add(entry);
            }

            ranked.sort(
                Comparator.<Map<String, Object>>comparingDouble(row -> ((Number) row.get("score")).doubleValue())
                    .reversed().thenComparing(row -> row.get("id").toString())
            );
            Map<String, Object> runFields = new LinkedHashMap<>();
            runFields.put("guest_session_id", guest);
            runFields.put("basis", "SELECTED_CV");
            runFields.put("profile_snapshot", db.json(Map.of("mode", "SKILL_OVERLAP", "demo_resume_id", resumeId)));
            runFields.put("provider", "RULE_BASED");
            runFields.put("model", "skill-overlap-v1");
            runFields.put("status", "COMPLETED");
            runFields.put("started_at", Instant.now());
            runFields.put("completed_at", Instant.now());

            UUID run = db.insertJobMatchingRuns(runFields);
            db.updateMatchingRunResumes(run, resumeId);

            List<Map<String, Object>> top = ranked.stream().limit(3).toList();

            for (int i = 0; i < top.size(); i++) {
                Map<String, Object> job = top.get(i);
                Map<?, ?> comparison = (Map<?, ?>) job.get("comparison");

                db.insertJobMatchResults(
                    Map.of(
                        "matching_run_id",
                        run,
                        "market_job_id",
                        job.get("id"),
                        "final_score",
                        job.get("score"),
                        "rank_position",
                        i + 1,
                        "match_reason_json",
                        db.json(Map.of("method", "SKILL_OVERLAP", "reason", comparison.get("reason"))),
                        "strengths_json",
                        db.json(comparison.get("strengths")),
                        "skill_gaps_json",
                        db.json(comparison.get("skill_gaps"))
                    )
                );
            }

            guests.settle(reservation, 100, true);

            return Map.of(
                "matching_run_id",
                run,
                "mode",
                "SKILL_OVERLAP",
                "trial",
                true,
                "results",
                top,
                "registration_prompt",
                "Đăng ký để nhận gợi ý dựa trên hồ sơ cá nhân."
            );
        } catch (RuntimeException ex) {
            guests.settle(reservation, 0, false);
            throw ex;
        }
    }

    private Map<String, Object> demo(
        UUID id) {
        return db.getResumesById(id);
    }
}
