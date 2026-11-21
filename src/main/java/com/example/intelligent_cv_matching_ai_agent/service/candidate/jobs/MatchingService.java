package com.example.intelligent_cv_matching_ai_agent.service.candidate.jobs;

import com.example.intelligent_cv_matching_ai_agent.util.shared.FlowSupport;

import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;

import com.example.intelligent_cv_matching_ai_agent.repository.candidate.jobs.MatchingRepository;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;

import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiResponseCache;

import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.jobs.SalaryExpectation;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.jobs.JobMatchRerank;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.jobs.QuickJobMatcher;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.jobs.RequirementEvidence;
import com.example.intelligent_cv_matching_ai_agent.service.shared.jobs.MatchingSalaryPolicyService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.jobs.SalaryExchangeRateService;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobSalaryParser;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.MatchingSalaryPolicy;
import java.time.Duration;
import java.time.Instant;
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

    private final AiResponseCache responseCache;

    private final MatchingSalaryPolicyService salaryPolicies;

    private final SalaryExchangeRateService exchangeRates;

    private final RequirementEvidenceService evidenceMatching;

    public MatchingService(
        MatchingRepository db,
        CurrentUser user,
        AiService ai,
        ResumeService resumes,
        ObjectProvider<StringRedisTemplate> redis,
        MatchingSalaryPolicyService salaryPolicies) {
        this(db, user, ai, resumes, redis, salaryPolicies, null);
    }

    public MatchingService(
        MatchingRepository db,
        CurrentUser user,
        AiService ai,
        ResumeService resumes,
        ObjectProvider<StringRedisTemplate> redis,
        MatchingSalaryPolicyService salaryPolicies,
        SalaryExchangeRateService exchangeRates) {
        this(db, user, ai, resumes, redis, salaryPolicies, exchangeRates, null);
    }

    @Autowired
    public MatchingService(
        MatchingRepository db,
        CurrentUser user,
        AiService ai,
        ResumeService resumes,
        ObjectProvider<StringRedisTemplate> redis,
        MatchingSalaryPolicyService salaryPolicies,
        SalaryExchangeRateService exchangeRates,
        RequirementEvidenceService evidenceMatching) {
        this.db = db;
        this.user = user;
        this.ai = ai;
        this.resumes = resumes;
        this.responseCache = new AiResponseCache(redis);
        this.salaryPolicies = salaryPolicies;
        this.exchangeRates = exchangeRates;
        this.evidenceMatching = evidenceMatching;
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

    public Optional<Map<String, Object>> evidenceForCv(
        Map<String, Object> cv,
        Map<String, Object> job) {
        if (evidenceMatching == null || !evidenceMatching.enabled())
            return Optional.empty();
        return Optional.of(evidenceMatching.assess(evidenceMatching.candidate(List.of(cv), Map.of(), List.of()), job));
    }

    @Transactional
    public Map<String, Object> match(
        String basis,
        List<UUID> selected,
        int limit) {
        return match(basis, selected, limit, "DETAILED");
    }

    @Transactional
    public Map<String, Object> match(
        String basis,
        List<UUID> selected,
        int limit,
        String requestedMode) {
        return match(basis, selected, limit, requestedMode, null);
    }

    @Transactional
    public Map<String, Object> match(
        String basis,
        List<UUID> selected,
        int limit,
        String requestedMode,
        UUID targetJobId) {
        user.requireRole("CANDIDATE");

        pageSize(limit);
        if (!Set.of("CAREER_PROFILE", "ALL_CVS", "SELECTED_CV").contains(basis))
            throw ApiException.badRequest("Invalid matching basis");
        if (requestedMode == null)
            requestedMode = "DETAILED";
        if (!Set.of("QUICK", "DETAILED").contains(requestedMode))
            throw ApiException.badRequest("Invalid matching mode");
        boolean quick = "QUICK".equals(requestedMode);

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
        QuickJobMatcher.Evidence quickEvidence = null;
        List<Map<String, Object>> profileSkills = new ArrayList<>();
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
            profileSkills = db.listCandidateProfileSkillsByUserId(user.id());
            if (quick) {
                quickEvidence = QuickJobMatcher.fromProfile(profile, profileSkills);
                candidate.append(quickEvidence);
            } else {
                candidate.append(profile);
                candidate.append(profileSkills);
            }
        } else {
            if (cvs.isEmpty())
                throw ApiException.badRequest("At least one confirmed CV is required");

            if (quick) {
                quickEvidence = QuickJobMatcher.combine(cvs.stream().map(QuickJobMatcher::fromResume).toList());
                candidate.append(quickEvidence);
            } else {
                cvs.forEach(cv -> candidate.append(resumes.canonicalText(cv)).append('\n'));
            }
            salary.ifPresent(
                expectation -> candidate.append("\nMonthly salary preference: ").append(expectation.policy())
            );
        }

        List<Map<String, Object>> catalog = targetJobId != null
            ? db.listMarketJobSkillsByIds("{" + targetJobId + "}")
            : salary.isPresent() ? db.listActiveJobSalaryMetadata() : db.listMarketJobSkillsByMarketJobId();
        if (catalog.isEmpty())
            throw ApiException.conflict(
                targetJobId == null
                    ? "No active jobs have been imported"
                    : "The selected job is inactive, expired or unavailable"
            );
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
        List<Map<String, Object>> jobs = salary.isPresent() && targetJobId == null
            ? salaryEligible(catalog, salary.get(), salaryMatches)
            : new ArrayList<>(catalog);
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
        salaryPolicy.put("filter_applied", salary.isPresent() && targetJobId == null);
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
        boolean useEvidence = targetJobId != null && !quick && evidenceMatching != null && evidenceMatching.enabled();
        boolean useAi = useEvidence || !quick && ai.enabled();
        if (salary.isPresent() && targetJobId == null && !useAi && !jobs.isEmpty())
            jobs = salaryEligible(db.listMarketJobSkillsByIds(eligibleIds), salary.get(), salaryMatches);

        String mode = useEvidence
            ? "REQUIREMENT_EVIDENCE"
            : quick ? "SKILL_OVERLAP_QUICK" : useAi ? "SEMANTIC_LLM" : "SKILL_OVERLAP";
        String catalogFingerprint = targetJobId == null
            ? text(db.readMarketJobPostsByStatus())
            : FlowSupport.hash(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(catalog));
        String vectorFingerprint =
            useAi && !useEvidence ? text(db.readMarketJobEmbeddingsByStatus(ai.provider(), ai.embeddingModel())) : "";
        List<String> resumeIds = cvs.stream().map(cv -> cv.get("id").toString()).sorted().toList();
        String evidenceFingerprint = useEvidence
            ? tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(
                basis.equals("CAREER_PROFILE")
                    ? RequirementEvidence.profileChunks(profile, profileSkills)
                    : RequirementEvidence.resumeChunks(cvs)
            )
            : "";
        String cacheHash = FlowSupport.hash(
            com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobRequirementExtractor.VERSION + "\n"
                + com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.SkillCatalog.get().version() + "\n"
                + basis + "\n" + resumeIds + "\n" + candidate + "\n" + catalogFingerprint + "\n" + vectorFingerprint
                + "\n" + mode + "\n" + (quick ? "RULE_BASED" : ai.provider()) + "\n"
                + (quick ? QuickJobMatcher.VERSION : ai.model()) + "\n" + (quick ? "" : ai.embeddingModel()) + "\n"
                + limit + "\n" + salaryPolicy + "\n" + salary.map(SalaryExpectation::exchangeRates) + "\n"
                + (useEvidence
                    ? RequirementEvidence.VERSION + "\n" + evidenceFingerprint + "\n"
                        + text(ai.cacheFingerprint(RequirementEvidence.FEATURE))
                    : quick ? QuickJobMatcher.VERSION : text(ai.cacheFingerprint("JOB_MATCH_RERANK")))
                + "\ntarget_job=" + targetJobId
        );
        String cacheKey = "edunexus:matching:response:v2:" + user.id() + ":" + cacheHash;
        Map<String, String> cacheIdentity = Map.of("user_id", user.id().toString(), "request_hash", cacheHash);
        var redisResult =
            responseCache.read(cacheKey, cacheIdentity).filter(result -> validCachedResult(result, basis, cacheHash));
        if (redisResult.isPresent()) {
            var result = redisResult.get();
            result.put("cached", true);
            result.put("cache_source", "REDIS");

            return result;
        }

        var cached = db.findJobMatchingRunsByUserId(user.id(), cacheHash);

        if (cached.isPresent()) {
            var result = result((UUID) cached.get().get("id"));
            result.put("cached", true);
            result.put("cache_source", "DATABASE");
            remember(cacheKey, cacheIdentity, result);

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
                useAi ? ai.provider() : "RULE_BASED",
                "model",
                useEvidence
                    ? RequirementEvidence.VERSION
                    : quick ? QuickJobMatcher.VERSION : useAi ? ai.model() : "skill-overlap-v1",
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
        } else if (useEvidence) {
            var prepared = evidenceMatching.candidate(cvs, profile, profileSkills);
            ranked = new ArrayList<>(db.listMarketJobSkillsByIds(eligibleIds));
            for (var job : ranked)
                job.put("verification_priority", evidenceMatching.confirmedDeclarationCoverage(prepared, job));
            ranked.sort(
                Comparator.<Map<String, Object>>comparingDouble(job -> number(job.get("verification_priority"), 0))
                    .reversed().thenComparing(job -> text(job.get("id")))
            );
            for (var job : ranked) {
                var report = evidenceMatching.assess(prepared, job);
                job.put("final_score", report.get("score"));
                job.put("explanation", report);
            }
        } else if (useAi) {
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
                        for (String key : List.of(
                            "original_content",
                            "raw_content",
                            "parsed_requirements",
                            "requirement_extraction",
                            "requirement_extraction_verified"
                        )) {
                            if (complete.containsKey(key))
                                job.put(key, complete.get(key));
                        }
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

                    var evidence = JobSkillOverlap.overlap(candidate.toString(), job);
                    for (String key : List.of(
                        "requirements",
                        "unverified_requirements",
                        "extraction_status",
                        "skill_coverage",
                        "requirement_coverage_available",
                        "catalog_version",
                        "coverage_complete"
                    )) {
                        explanation.put(key, evidence.get(key));
                    }
                    explanation.put("score_kind", "SEMANTIC_SUITABILITY_ESTIMATE");
                    job.put("final_score", explanation.get("score"));
                    job.put("explanation", explanation);
                }
            }
        } else {
            ranked = new ArrayList<>(jobs);
            if (quick) {
                for (var job : ranked) {
                    var assessment = QuickJobMatcher.assess(quickEvidence, job);
                    job.put("final_score", assessment.get("score"));
                    job.put("explanation", assessment);
                }
                ranked.removeIf(job -> !Boolean.TRUE.equals(object(job.get("explanation")).get("eligible")));
            } else {
                rankSkills(candidate.toString(), ranked);
            }
        }

        ranked
            .sort(
                Comparator
                    .<Map<String, Object>>comparingInt(
                        j -> useEvidence
                            && !Boolean.TRUE.equals(object(j.get("explanation")).get("requirement_coverage_available"))
                                ? 1
                                : 0
                    )
                    .thenComparing(
                        Comparator.<Map<String, Object>>comparingDouble(j -> number(j.get("final_score"), 0)).reversed()
                    )
                    .thenComparing(
                        Comparator.<Map<String, Object>>comparingDouble(
                            j -> useEvidence ? number(object(j.get("explanation")).get("preferred_coverage"), 0) : 0
                        ).reversed()
                    )
                    .thenComparing(
                        Comparator.<Map<String, Object>>comparingInt(
                            j -> quick && !useEvidence ? array(object(j.get("explanation")).get("strengths")).size() : 0
                        ).reversed()
                    )
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
                            useEvidence ? "REQUIREMENT_CHUNKS" : job.get("vector_score") == null ? "SKILL" : "VECTOR",
                            "reason",
                            explanation.get("reason"),
                            "assessment",
                            useEvidence
                                ? explanation
                                : fields(
                                    "requirement_coverage_available",
                                    explanation.get("requirement_coverage_available"),
                                    "requirement_coverage",
                                    explanation.get("requirement_coverage"),
                                    "skill_coverage",
                                    explanation.get("skill_coverage"),
                                    "experience_status",
                                    explanation.get("experience_status"),
                                    "candidate_years",
                                    explanation.get("candidate_years"),
                                    "required_years",
                                    explanation.get("required_years"),
                                    "requirements",
                                    explanation.get("requirements"),
                                    "unverified_requirements",
                                    explanation.get("unverified_requirements"),
                                    "extraction_status",
                                    explanation.get("extraction_status"),
                                    "catalog_version",
                                    explanation.get("catalog_version"),
                                    "coverage_complete",
                                    explanation.get("coverage_complete"),
                                    "score_kind",
                                    explanation.get("score_kind")
                                ),
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

        if (useEvidence)
            db.updateEvidenceStatus(
                runId,
                ranked.stream()
                    .anyMatch(job -> "DEFERRED".equals(object(job.get("explanation")).get("verification_status")))
            );
        db.updateJobMatchingRunsById(runId);

        var result = result(runId);
        result.put("cached", false);
        remember(cacheKey, cacheIdentity, result);

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

    private boolean validCachedResult(
        Map<String, Object> result,
        String basis,
        String cacheHash) {
        try {
            UUID runId = UUID.fromString(text(result.get("id")));
            Duration lifetime = cacheLifetime(result);
            if (!user.id().toString().equals(text(result.get("user_id"))) || !basis.equals(result.get("basis"))
                || !"COMPLETED".equals(result.get("status"))
                || !cacheHash.equals(object(result.get("profile_snapshot")).get("request_hash"))
                || !(result.get("results") instanceof List<?> rows) || lifetime.compareTo(Duration.ZERO) <= 0) {
                return false;
            }
            for (Object value : rows) {
                Map<String, Object> row = object(value);
                if (!runId.toString().equals(text(row.get("matching_run_id")))
                    || !"ACTIVE".equals(row.get("job_status"))) {
                    return false;
                }
                UUID.fromString(text(row.get("id")));
                UUID.fromString(text(row.get("market_job_id")));
            }
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private void remember(
        String cacheKey,
        Map<String, String> identity,
        Map<String, Object> result) {
        Duration lifetime = cacheLifetime(result);
        if (!lifetime.isNegative() && !lifetime.isZero()) {
            responseCache.putAfterCommit(cacheKey, identity, result, lifetime);
        }
    }

    private static Duration cacheLifetime(
        Map<String, Object> result) {
        if ("DEFERRED".equals(object(result.get("profile_snapshot")).get("evidence_status")))
            return Duration.ZERO;
        Instant completed = instant(result.get("completed_at"));
        if (completed == null) {
            return Duration.ZERO;
        }
        Instant now = Instant.now();
        Instant expires = completed.plus(Duration.ofHours(1));
        if (expires.isAfter(now.plus(Duration.ofHours(1)))) {
            return Duration.ZERO;
        }
        for (Object value : array(result.get("results"))) {
            Map<String, Object> row = object(value);
            if (row.get("valid_through") != null) {
                Instant deadline = instant(row.get("valid_through"));
                if (deadline == null) {
                    return Duration.ZERO;
                }
                if (deadline.isBefore(expires)) {
                    expires = deadline;
                }
            }
        }
        return Duration.between(now, expires);
    }

    private static Instant instant(
        Object value) {
        try {
            if (value instanceof Instant time) {
                return time;
            }
            if (value instanceof java.util.Date time) {
                return time.toInstant();
            }
            if (value instanceof OffsetDateTime time) {
                return time.toInstant();
            }
            return OffsetDateTime.parse(text(value)).toInstant();
        } catch (java.time.DateTimeException ex) {
            return null;
        }
    }

    public static Map<String, Object> overlap(
        String candidate,
        Map<String, Object> job) {
        return JobSkillOverlap.overlap(candidate, job);
    }
}
