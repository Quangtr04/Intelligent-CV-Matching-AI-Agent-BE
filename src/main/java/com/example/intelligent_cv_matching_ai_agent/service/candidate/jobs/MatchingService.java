package com.example.intelligent_cv_matching_ai_agent.service.candidate.jobs;

import com.example.intelligent_cv_matching_ai_agent.util.shared.FlowSupport;

import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;

import com.example.intelligent_cv_matching_ai_agent.repository.candidate.jobs.MatchingRepository;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;

import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;

import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

@Service
public class MatchingService {

    private final MatchingRepository db;

    private final CurrentUser user;

    private final AiService ai;

    private final ResumeService resumes;

    public MatchingService(
        MatchingRepository db,
        CurrentUser user,
        AiService ai,
        ResumeService resumes) {
        this.db = db;
        this.user = user;
        this.ai = ai;
        this.resumes = resumes;
    }

    public Map<String, Object> result(
        UUID runId) {
        var run = db.getJobMatchingRunsById(runId, user.id());
        run.put("results", db.listJobMatchResultsByMatchingRunId(runId));

        return run;
    }

    @Transactional
    public Map<String, Object> match(
        String basis,
        List<UUID> selected,
        int limit) {
        user.requireRole("CANDIDATE");

        pageSize(limit);
        if (!Set.of("CAREER_PROFILE", "ALL_CVS", "SELECTED_CV").contains(basis))
            throw ApiException.badRequest("Invalid matching basis");

        List<Map<String, Object>> cvs = new ArrayList<>();

        if (basis.equals("ALL_CVS")) {
            cvs = db.listResumesByUserId(user.id());
            if (cvs.size() > 100)
                throw ApiException.badRequest("ALL_CVS supports at most 100 confirmed resumes per request");
        }

        if (basis.equals("SELECTED_CV")) {
            if (selected == null || selected.size() != 1)
                throw ApiException.badRequest("SELECTED_CV requires exactly one resume ID");

            var resume = resumes.owned(selected.getFirst());
            if (!"READY".equals(resume.get("status")))
                throw ApiException.conflict("Confirm CV data before matching");

            cvs.add(resume);
        }

        StringBuilder candidate = new StringBuilder();

        if (basis.equals("CAREER_PROFILE")) {
            var profile = db.findCandidateProfilesByUserId(user.id())
                .orElseThrow(() -> ApiException.badRequest("Complete your career profile first"));
            candidate.append(profile);
            candidate.append(db.listCandidateProfileSkillsByUserId(user.id()));
        } else {
            if (cvs.isEmpty())
                throw ApiException.badRequest("At least one confirmed CV is required");

            cvs.forEach(cv -> candidate.append(resumes.canonicalText(cv)).append('\n'));
        }

        List<Map<String, Object>> jobs = db.listMarketJobSkillsByMarketJobId();
        if (jobs.isEmpty())
            throw ApiException.conflict("No active jobs have been imported");

        String mode = ai.enabled() ? "SEMANTIC_LLM" : "SKILL_OVERLAP";
        String catalogFingerprint = text(db.readMarketJobPostsByStatus());
        String vectorFingerprint =
            ai.enabled() ? text(db.readMarketJobEmbeddingsByStatus(ai.provider(), ai.embeddingModel())) : "";
        List<String> resumeIds = cvs.stream().map(cv -> cv.get("id").toString()).sorted().toList();
        String cacheHash = FlowSupport.hash(
            basis + "\n" + resumeIds + "\n" + candidate + "\n" + catalogFingerprint + "\n" + vectorFingerprint + "\n"
                + mode + "\n" + ai.provider() + "\n" + ai.model() + "\n" + ai.embeddingModel() + "\n" + limit
        );
        var cached = db.findJobMatchingRunsByUserId(user.id(), cacheHash);

        if (cached.isPresent()) {
            var result = result((UUID) cached.get().get("id"));
            result.put("cached", true);

            return result;
        }

        UUID runId = db.insertJobMatchingRuns(
            fields(
                "user_id",
                user.id(),
                "basis",
                basis,
                "profile_snapshot",
                db.json(fields("request_hash", cacheHash, "mode", mode, "candidate", candidate.toString())),
                "provider",
                ai.enabled() ? ai.provider() : "RULE_BASED",
                "model",
                ai.enabled() ? ai.model() : "skill-overlap-v1",
                "status",
                "PROCESSING",
                "started_at",
                OffsetDateTime.now()
            )
        );

        for (var cv : cvs)
            db.updateMatchingRunResumes(runId, cv.get("id"));

        List<Map<String, Object>> ranked;

        if (ai.enabled()) {
            String vector = FlowSupport.vector(ai.embed(candidate.toString()));
            ranked = db.listMarketJobEmbeddingsByStatus(
                vector,
                ai.provider(),
                ai.embeddingModel(),
                vector,
                Math.min(100, Math.max(limit, 20))
            );
            if (ranked.isEmpty())
                throw ApiException.conflict("Index active jobs with the configured embedding model first");

            Map<String, Object> generated =
                ai.generate("JOB_MATCH_RERANK", fields("candidate", candidate.toString(), "jobs", ranked));
            Map<UUID, Map<String, Object>> aiResults = new HashMap<>();

            for (Object raw : array(generated.get("results"))) {
                Map<String, Object> item = object(raw);
                UUID id = uuid(item.get("job_id"));
                if (aiResults.put(id, item) != null)
                    throw new ApiException(502, "AI returned duplicate job IDs");

                FlowSupport.score(item.get("score"), 1, "match score");
            }

            for (var job : ranked) {
                var explanation = aiResults.get(job.get("id"));
                if (explanation == null)
                    throw new ApiException(502, "AI ranking omitted a retrieved job");

                job.put("final_score", explanation.get("score"));
                job.put("explanation", explanation);
            }
        } else {
            ranked = new ArrayList<>(jobs);

            for (var job : ranked) {
                var overlap = overlap(candidate.toString(), job);
                job.put("final_score", overlap.get("score"));
                job.put("explanation", overlap);
            }
        }

        ranked.sort(
            Comparator.<Map<String, Object>>comparingDouble(j -> number(j.get("final_score"), 0)).reversed()
                .thenComparing(j -> j.get("id").toString())
        );

        for (int i = 0; i < Math.min(limit, ranked.size()); i++) {
            var job = ranked.get(i);
            var explanation = object(job.get("explanation"));

            db.insertJobMatchResults(
                fields(
                    "matching_run_id",
                    runId,
                    "market_job_id",
                    job.get("id"),
                    "vector_similarity_score",
                    job.get("vector_score"),
                    "llm_rerank_score",
                    ai.enabled() ? job.get("final_score") : null,
                    "final_score",
                    job.get("final_score"),
                    "rank_position",
                    i + 1,
                    "match_reason_json",
                    db.json(fields("method", mode, "reason", explanation.get("reason"))),
                    "strengths_json",
                    db.json(array(explanation.get("strengths"))),
                    "skill_gaps_json",
                    db.json(array(explanation.get("skill_gaps")))
                )
            );
        }

        db.updateJobMatchingRunsById(runId);

        var result = result(runId);
        result.put("cached", false);

        return result;
    }

    public static Map<String, Object> overlap(
        String candidate,
        Map<String, Object> job) {
        String lower = " " + candidate.toLowerCase(Locale.ROOT) + " ";
        List<String> strengths = new ArrayList<>(), gaps = new ArrayList<>();
        int requiredCount = 0, matched = 0;

        for (Object raw : array(job.get("skills"))) {
            var skill = object(raw);
            String name = text(skill.get("name")).toLowerCase(Locale.ROOT);
            boolean required = !Boolean.FALSE.equals(skill.get("required"));
            boolean found = java.util.regex.Pattern
                .compile("(?<![\\p{L}\\p{N}])" + java.util.regex.Pattern.quote(name) + "(?![\\p{L}\\p{N}])")
                .matcher(lower).find();

            if (found)
                strengths.add(name);
            else if (required)
                gaps.add(name);

            if (required) {
                requiredCount++;

                if (found)
                    matched++;
            }
        }

        double score = requiredCount == 0 ? 0.0 : (double) matched / requiredCount;

        return fields(
            "score",
            score,
            "reason",
            requiredCount == 0
                ? "No structured job skills available for comparison"
                : "Matched " + matched + " of " + requiredCount + " required skills; keyword comparison",
            "strengths",
            strengths,
            "skill_gaps",
            gaps
        );
    }
}
