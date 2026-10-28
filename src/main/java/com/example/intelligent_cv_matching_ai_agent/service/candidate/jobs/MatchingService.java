package com.example.intelligent_cv_matching_ai_agent.service.candidate.jobs;

import com.example.intelligent_cv_matching_ai_agent.util.shared.FlowSupport;

import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;

import com.example.intelligent_cv_matching_ai_agent.repository.candidate.jobs.MatchingRepository;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;

import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;

import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.jobs.SalaryExpectation;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.jobs.JobMatchRerank;
import com.example.intelligent_cv_matching_ai_agent.service.shared.jobs.MatchingSalaryPolicyService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.jobs.SalaryExchangeRateService;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobSalaryParser;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.MatchingSalaryPolicy;
import java.time.OffsetDateTime;
import java.util.*;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.jobs.JobSkillOverlap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

@Service
public class MatchingService {

    private final MatchingRepository db;

    private final CurrentUser user;

    private final AiService ai;

    private final ResumeService resumes;

    private final ObjectProvider<StringRedisTemplate> redis;

    private final MatchingSalaryPolicyService salaryPolicies;

    private final SalaryExchangeRateService exchangeRates;

    public MatchingService(
        MatchingRepository db,
        CurrentUser user,
        AiService ai,
        ResumeService resumes,
        ObjectProvider<StringRedisTemplate> redis,
        MatchingSalaryPolicyService salaryPolicies) {
        this(db, user, ai, resumes, redis, salaryPolicies, null);
    }

    @Autowired
    public MatchingService(
        MatchingRepository db,
        CurrentUser user,
        AiService ai,
        ResumeService resumes,
        ObjectProvider<StringRedisTemplate> redis,
        MatchingSalaryPolicyService salaryPolicies,
        SalaryExchangeRateService exchangeRates) {
        this.db = db;
        this.user = user;
        this.ai = ai;
        this.resumes = resumes;
        this.redis = redis;
        this.salaryPolicies = salaryPolicies;
        this.exchangeRates = exchangeRates;
    }

    public Map<String, Object> result(
        UUID runId) {
        var run = db.getJobMatchingRunsById(runId, user.id());
        run.put("results", db.listJobMatchResultsByMatchingRunId(runId));
        Map<String, Object> snapshot = object(run.get("profile_snapshot"));
        if (snapshot.containsKey("salary_filter"))
            run.put("salary_filter", snapshot.get("salary_filter"));

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

        MatchingSalaryPolicy settings = salaryPolicies.effective();
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
        Optional<Map<String, Object>> storedProfile = db.findCandidateProfilesByUserId(user.id());
        Map<String, Object> profile = new LinkedHashMap<>(storedProfile.orElse(Map.of()));
        profile.remove("expected_salary_min");
        profile.remove("expected_salary_max");
        Optional<SalaryExpectation> salary = SalaryExpectation.fromProfile(profile, settings);

        if (basis.equals("CAREER_PROFILE")) {
            if (storedProfile.isEmpty())
                throw ApiException.badRequest("Complete your career profile first");
            if (salary.isEmpty())
                throw ApiException
                    .badRequest("Set expectedSalary in your career profile before requesting job recommendations");
            candidate.append(profile);
            candidate.append(db.listCandidateProfileSkillsByUserId(user.id()));
        } else {
            if (cvs.isEmpty())
                throw ApiException.badRequest("At least one confirmed CV is required");

            cvs.forEach(cv -> candidate.append(resumes.canonicalText(cv)).append('\n'));
            salary.ifPresent(
                expectation -> candidate.append("\nMonthly salary preference: ").append(expectation.policy())
            );
        }

        List<Map<String, Object>> catalog =
            salary.isPresent() ? db.listActiveJobSalaryMetadata() : db.listMarketJobSkillsByMarketJobId();
        if (catalog.isEmpty())
            throw ApiException.conflict("No active jobs have been imported");
        if (salary.isPresent() && exchangeRates != null) {
            String expectedCurrency = salary.get().currency();
            boolean needsConversion =
                catalog.stream().map(job -> JobSalaryParser.parseMonthly(text(job.get("salary_text"))))
                    .anyMatch(parsed -> parsed.isPresent() && !expectedCurrency.equals(parsed.get().currency()));
            if (needsConversion) {
                salary = SalaryExpectation.fromProfile(profile, settings, exchangeRates.snapshot());
            }
        }
        Map<UUID, Map<String, Object>> salaryMatches = new HashMap<>();
        List<Map<String, Object>> jobs =
            salary.isPresent() ? salaryEligible(catalog, salary.get(), salaryMatches) : new ArrayList<>(catalog);
        Map<String, Object> salaryPolicy = salary.map(SalaryExpectation::policy).orElseGet(
            () -> fields(
                "active",
                false,
                "policy_version",
                SalaryExpectation.POLICY_VERSION,
                "tolerance_percent",
                settings.tolerancePercent(),
                "minimum_ratio",
                settings.minimumRatio(),
                "settings_version",
                settings.version()
            )
        );
        salaryPolicy.put("examined_jobs", catalog.size());
        salaryPolicy.put("eligible_jobs", jobs.size());
        salaryPolicy.put(
            "verified_eligible_jobs",
            salaryMatches.values().stream().filter(match -> Boolean.TRUE.equals(match.get("verified"))).count()
        );
        salaryPolicy.put(
            "unverified_eligible_jobs",
            salaryMatches.values().stream().filter(match -> Boolean.FALSE.equals(match.get("verified"))).count()
        );
        String eligibleIds = jobIds(jobs);
        if (salary.isPresent() && !ai.enabled() && !jobs.isEmpty())
            jobs = salaryEligible(db.listMarketJobSkillsByIds(eligibleIds), salary.get(), salaryMatches);

        String mode = ai.enabled() ? "SEMANTIC_LLM" : "SKILL_OVERLAP";
        String catalogFingerprint = text(db.readMarketJobPostsByStatus());
        String vectorFingerprint =
            ai.enabled() ? text(db.readMarketJobEmbeddingsByStatus(ai.provider(), ai.embeddingModel())) : "";
        List<String> resumeIds = cvs.stream().map(cv -> cv.get("id").toString()).sorted().toList();
        String cacheHash = FlowSupport.hash(
            basis + "\n" + resumeIds + "\n" + candidate + "\n" + catalogFingerprint + "\n" + vectorFingerprint + "\n"
                + mode + "\n" + ai.provider() + "\n" + ai.model() + "\n" + ai.embeddingModel() + "\n" + limit + "\n"
                + salaryPolicy + "\n" + salary.map(SalaryExpectation::exchangeRates)
        );
        String cacheKey = "edunexus:matching:" + user.id() + ":" + cacheHash;
        Optional<UUID> redisRun = cachedRun(cacheKey);
        if (redisRun.isPresent()) {
            var result = result(redisRun.get());
            result.put("cached", true);
            result.put("cache_source", "REDIS");

            return result;
        }

        var cached = db.findJobMatchingRunsByUserId(user.id(), cacheHash);

        if (cached.isPresent()) {
            var result = result((UUID) cached.get().get("id"));
            result.put("cached", true);
            result.put("cache_source", "DATABASE");
            remember(cacheKey, (UUID) cached.get().get("id"));

            return result;
        }

        UUID runId = db.insertJobMatchingRuns(
            fields(
                "user_id",
                user.id(),
                "basis",
                basis,
                "profile_snapshot",
                db.json(
                    fields(
                        "request_hash",
                        cacheHash,
                        "mode",
                        mode,
                        "candidate",
                        candidate.toString(),
                        "salary_filter",
                        salaryPolicy
                    )
                ),
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
        boolean semanticUsed = false;

        if (jobs.isEmpty()) {
            ranked = new ArrayList<>();
        } else if (ai.enabled()) {
            if (vectorFingerprint.isBlank()) {
                ranked = new ArrayList<>();
            } else {
                String vector = FlowSupport.vector(ai.embed(candidate.toString()));
                ranked = salary.isPresent()
                    ? db.listMarketJobEmbeddingsByStatusForIds(
                        ai.provider(),
                        ai.embeddingModel(),
                        eligibleIds,
                        vector,
                        vector,
                        Math.min(100, Math.max(limit, 20))
                    )
                    : db.listMarketJobEmbeddingsByStatus(
                        vector,
                        ai.provider(),
                        ai.embeddingModel(),
                        vector,
                        Math.min(100, Math.max(limit, 20))
                    );
            }
            if (!ranked.isEmpty() && salary.isPresent())
                ranked = salaryEligible(ranked, salary.get(), salaryMatches);
            if (ranked.isEmpty()) {
                mode = "SKILL_OVERLAP_UNINDEXED_JOBS";
                List<Map<String, Object>> fallback = db.listMarketJobSkillsByIds(eligibleIds);
                ranked = salary.isPresent()
                    ? salaryEligible(fallback, salary.get(), salaryMatches)
                    : new ArrayList<>(fallback);
                rankSkills(candidate.toString(), ranked);
                db.updateJobMatchingRunsMode(runId, mode, "RULE_BASED", "skill-overlap-v1");
            } else {
                ranked = new ArrayList<>(ranked);
                Set<UUID> retrievedIds = new HashSet<>();
                for (var job : ranked) {
                    retrievedIds.add(uuid(job.get("id")));
                }
                List<Map<String, Object>> supplements = new ArrayList<>();
                Map<UUID, Map<String, Object>> completeJobs = new HashMap<>();
                for (var job : db.listMarketJobSkillsByIds(eligibleIds)) {
                    completeJobs.put(uuid(job.get("id")), job);
                    if (!retrievedIds.contains(uuid(job.get("id")))) {
                        supplements.add(new LinkedHashMap<>(job));
                    }
                }
                if (salary.isPresent()) {
                    supplements = salaryEligible(supplements, salary.get(), salaryMatches);
                }
                rankSkills(candidate.toString(), supplements);
                supplements.sort(
                    Comparator.<Map<String, Object>>comparingDouble(job -> number(job.get("final_score"), 0)).reversed()
                        .thenComparing(job -> job.get("id").toString())
                );
                int available = Math.max(0, 100 - ranked.size());
                ranked.addAll(
                    supplements.stream().filter(job -> number(job.get("final_score"), 0) > 0)
                        .limit(Math.min(20, available)).toList()
                );
                for (var job : ranked) {
                    var complete = completeJobs.get(uuid(job.get("id")));
                    if (complete != null) {
                        job.put("skills", complete.get("skills"));
                    }
                }
                semanticUsed = true;
                Map<UUID, Map<String, Object>> aiResults = new HashMap<>();
                for (var batch : JobMatchRerank.batches(ranked)) {
                    var generated =
                        ai.generate("JOB_MATCH_RERANK", fields("candidate", candidate.toString(), "jobs", batch));
                    aiResults.putAll(JobMatchRerank.validate(batch, generated));
                }

                for (var job : ranked) {
                    var explanation = aiResults.get(job.get("id"));
                    if (explanation == null)
                        throw new ApiException(502, "AI ranking omitted a retrieved job");

                    job.put("final_score", explanation.get("score"));
                    job.put("explanation", explanation);
                }
            }
        } else {
            ranked = new ArrayList<>(jobs);

            rankSkills(candidate.toString(), ranked);
        }

        ranked
            .sort(
                Comparator.<Map<String, Object>>comparingDouble(j -> number(j.get("final_score"), 0)).reversed()
                    .thenComparingInt(
                        j -> Boolean.FALSE
                            .equals(salaryMatches.getOrDefault(uuid(j.get("id")), Map.of()).get("verified")) ? 1 : 0
                    )
                    .thenComparingDouble(
                        j -> number(salaryMatches.getOrDefault(uuid(j.get("id")), Map.of()).get("shortfall_percent"), 0)
                    ).thenComparing(j -> j.get("id").toString())
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
                    semanticUsed ? job.get("final_score") : null,
                    "final_score",
                    job.get("final_score"),
                    "rank_position",
                    i + 1,
                    "match_reason_json",
                    db.json(
                        fields(
                            "method",
                            mode,
                            "retrieval",
                            job.get("vector_score") == null ? "SKILL" : "VECTOR",
                            "reason",
                            explanation.get("reason"),
                            "salary_match",
                            salaryMatches.get(uuid(job.get("id")))
                        )
                    ),
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
        remember(cacheKey, runId);

        return result;
    }

    private static List<Map<String, Object>> salaryEligible(
        List<Map<String, Object>> jobs,
        SalaryExpectation expectation,
        Map<UUID, Map<String, Object>> matches) {
        List<Map<String, Object>> eligible = new ArrayList<>();
        for (Map<String, Object> job : jobs) {
            expectation.match(text(job.get("salary_text"))).ifPresent(match -> {
                matches.put(uuid(job.get("id")), match);
                eligible.add(new LinkedHashMap<>(job));
            });
        }
        return eligible;
    }

    private static void rankSkills(
        String candidate,
        List<Map<String, Object>> jobs) {
        for (var job : jobs) {
            var overlap = overlap(candidate, job);
            job.put("final_score", overlap.get("score"));
            job.put("explanation", overlap);
        }
    }

    private static String jobIds(
        List<Map<String, Object>> jobs) {
        StringJoiner ids = new StringJoiner(",", "{", "}");
        for (Map<String, Object> job : jobs)
            ids.add(uuid(job.get("id")).toString());
        return ids.toString();
    }

    private Optional<UUID> cachedRun(
        String cacheKey) {
        try {
            var client = redis.getIfAvailable();
            String cached = client == null ? null : client.opsForValue().get(cacheKey);
            if (cached == null)
                return Optional.empty();

            return Optional.of(UUID.fromString(cached));
        } catch (org.springframework.data.redis.RedisConnectionFailureException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private void remember(
        String cacheKey,
        UUID runId) {
        try {
            var client = redis.getIfAvailable();
            if (client != null)
                client.opsForValue().set(cacheKey, runId.toString(), java.time.Duration.ofHours(1));
        } catch (org.springframework.data.redis.RedisConnectionFailureException ex) {
        }
    }

    public static Map<String, Object> overlap(
        String candidate,
        Map<String, Object> job) {
        return JobSkillOverlap.overlap(candidate, job);
    }
}
