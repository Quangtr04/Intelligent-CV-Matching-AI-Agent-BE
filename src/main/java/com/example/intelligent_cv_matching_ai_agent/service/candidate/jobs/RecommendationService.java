package com.example.intelligent_cv_matching_ai_agent.service.candidate.jobs;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.jobs.*;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiResponseCache;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.jobs.MatchingSalaryPolicyService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.jobs.SalaryExchangeRateService;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.jobs.*;
import com.example.intelligent_cv_matching_ai_agent.util.shared.FlowSupport;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobEmbeddingSupport;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.SkillCatalog;
import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

/** Recommendation has its own routing and cache identity; QUICK and CV-JD assessments retain their contracts. */
@Service
public class RecommendationService {

    public static final String VERSION = "recommendation-v4-top-ten";

    private static final int MAX_RESULTS = 10;

    private static final String CANDIDATE_VECTOR_CACHE_VERSION = "recommendation-v1";

    private static final Logger LOG = LoggerFactory.getLogger(RecommendationService.class);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final MatchingRepository matching;

    private final RecommendationRepository jobs;

    private final RequirementEvidenceRepository vectors;

    private final CurrentUser user;

    private final AiService ai;

    private final ResumeService resumes;

    private final MatchingSalaryPolicyService policies;

    private final SalaryExchangeRateService rates;

    private final AiResponseCache cache;

    @Value("${app.recommendation.rerank-enabled:true}")
    private boolean rerankEnabled = true;

    @Value("${app.recommendation.cache-seconds:600}")
    private int cacheSeconds = 600;

    public RecommendationService(
        MatchingRepository matching,
        RecommendationRepository jobs,
        RequirementEvidenceRepository vectors,
        CurrentUser user,
        AiService ai,
        ResumeService resumes,
        MatchingSalaryPolicyService policies,
        SalaryExchangeRateService rates,
        ObjectProvider<StringRedisTemplate> redis) {
        this.matching = matching;
        this.jobs = jobs;
        this.vectors = vectors;
        this.user = user;
        this.ai = ai;
        this.resumes = resumes;
        this.policies = policies;
        this.rates = rates;
        cache = new AiResponseCache(redis);
    }

    @Transactional
    public Map<String, Object> recommend(
        String basis,
        List<UUID> selected,
        int limit) {
        user.requireRole("CANDIDATE");
        int resultLimit = Math.min(pageSize(limit), MAX_RESULTS);
        if (!Set.of("SELECTED_CV", "ALL_CVS", "CAREER_PROFILE").contains(Objects.toString(basis, "")))
            throw ApiException.badRequest("Invalid recommendation basis");
        long started = System.nanoTime(), stage = started;
        Map<String, Object> timings = new LinkedHashMap<>();
        var profile = matching.findCandidateProfilesByUserId(user.id()).orElse(Map.of());
        List<Map<String, Object>> cvs = new ArrayList<>();
        if (basis.equals("SELECTED_CV")) {
            if (selected == null || selected.size() != 1)
                throw ApiException.badRequest("SELECTED_CV requires exactly one resume ID");
            cvs.add(resumes.owned(selected.getFirst()));
        } else if (basis.equals("ALL_CVS")) {
            cvs.addAll(matching.listResumesByUserId(user.id()));
            if (cvs.size() > 100)
                throw ApiException.badRequest("ALL_CVS supports at most 100 confirmed resumes");
        }
        if (!basis.equals("CAREER_PROFILE") && cvs.isEmpty())
            throw ApiException
                .conflict(("At least one confirmed CV is required; select CAREER_PROFILE to use " + "your profile"));
        if (basis.equals("CAREER_PROFILE") && profile.isEmpty())
            throw ApiException.badRequest("Complete your career profile first");
        var documents = CandidateSearchProfile.normalize(
            basis,
            cvs,
            profile,
            basis.equals("CAREER_PROFILE") ? matching.listCandidateProfileSkillsByUserId(user.id()) : List.of()
        );
        timings.put("normalization_ms", elapsed(stage));
        String provider = ai.embeddingProvider(), model = ai.embeddingModel();
        int dimensions = ai.embeddingDimensions();
        if (dimensions != 1024)
            throw ApiException.conflict("Configured embedding dimensions must match PostgreSQL vector(1024)");
        var catalog = jobs.vectorCatalog(provider, model, dimensions);
        var settings = policies.effective();
        var salary = SalaryExpectation.fromProfile(profile, settings);
        if (salary.isPresent() && rates != null)
            salary = SalaryExpectation.fromProfile(profile, settings, rates.snapshot());
        Map<UUID, Map<String, Object>> salaryMatches = new HashMap<>();
        Set<UUID> eligible = null;
        if (salary.isPresent()) {
            eligible = new LinkedHashSet<>();
            for (var job : matching.listActiveJobSalaryMetadata()) {
                var assessment = salary.get().match(text(job.get("salary_text")));
                if (assessment.isPresent()) {
                    UUID id = uuid(job.get("id"));
                    eligible.add(id);
                    salaryMatches.put(id, assessment.get());
                }
            }
        }
        var salaryPolicy = salary.map(SalaryExpectation::policy).orElse(fields("active", false));
        salaryPolicy.put("filter_applied", salary.isPresent());
        salaryPolicy.put("eligible_jobs", eligible == null ? catalog.get("active_jobs") : eligible.size());
        String hash = FlowSupport.hash(
            VERSION + "\n" + RecommendationRules.VERSION + "\n" + CandidateSearchProfile.VERSION + "\n"
                + SkillCatalog.get().version() + "\n" + JobEmbeddingSupport.VERSION + "\n" + basis + "\n"
                + JSON.writeValueAsString(
                    documents.stream().map(doc -> fields("hash", doc.contentHash(), "resumes", doc.resumeIds()))
                        .toList()
                ) + "\n" + provider + "\n" + model + "\n" + dimensions + "\n" + ai.model() + "\n"
                + ai.cacheFingerprint("JOB_MATCH_RERANK") + "\n" + salaryPolicy + "\n"
                + salary.map(SalaryExpectation::exchangeRates) + "\n" + matching.readMarketJobPostsByStatus() + "\n"
                + catalog + "\n" + resultLimit + ":" + rerankEnabled + ":" + ai.enabled() + ":" + ai.embeddingsEnabled()
        );
        String cacheKey = "edunexus:recommendation:v3:" + user.id() + ":" + hash;
        var identity = Map.of("user_id", user.id().toString(), "request_hash", hash);
        // Reload persisted results on every cache hit, preserving live job status and user ownership.
        var cachedId = cache.read(cacheKey, identity).map(row -> uuid(row.get("id")));
        if (cachedId.isEmpty())
            cachedId = jobs.cachedRun(user.id(), hash, bounded(cacheSeconds, 1, 3600)).map(row -> uuid(row.get("id")));
        if (cachedId.isPresent()) {
            var result = result(cachedId.get(), true);
            LOG.info("recommendation cache_hit=true run={} total_ms={}", cachedId.get(), elapsed(started));
            return result;
        }

        List<float[]> candidateVectors = new ArrayList<>();
        String semanticStatus = "USED", fallbackReason = "";
        boolean cacheable = true;
        int embeddingCalls = 0;
        stage = System.nanoTime();
        if (number(catalog.get("compatible_vectors"), 0) == 0) {
            semanticStatus = "NO_COMPATIBLE_VECTORS";
            fallbackReason = "No current vectors for the configured provider/model/dimensions; backfill required";
        } else {
            for (var document : documents) {
                String vectorHash = FlowSupport
                    .hash(CANDIDATE_VECTOR_CACHE_VERSION + "\n" + dimensions + "\n" + document.contentHash());
                var stored = vectors.vector(user.id(), provider, model, vectorHash);
                try {
                    float[] vector;
                    if (stored.isPresent()) {
                        vector = stored.get();
                    } else {
                        embeddingCalls++;
                        vector = ai.embed(document.searchText());
                    }
                    validateVector(vector, dimensions);
                    if (stored.isEmpty())
                        vectors.saveVector(user.id(), provider, model, vectorHash, vector);
                    candidateVectors.add(vector);
                } catch (ApiException failure) {
                    if (!recoverable(failure))
                        throw failure;
                    semanticStatus = "PROVIDER_ERROR";
                    fallbackReason = "Candidate embedding unavailable (HTTP " + failure.status() + ")";
                    cacheable = false;
                    candidateVectors.clear();
                    break;
                }
            }
        }
        timings.put("embedding_ms", elapsed(stage));
        stage = System.nanoTime();
        Map<UUID, Map<String, Object>> retrieved = new LinkedHashMap<>();
        String eligibleIds =
            eligible == null ? null : "{" + String.join(",", eligible.stream().map(UUID::toString).toList()) + "}";
        for (int i = 0; i < candidateVectors.size(); i++) {
            for (var source : jobs.semanticCandidates(
                provider,
                model,
                dimensions,
                Arrays.toString(candidateVectors.get(i)),
                eligibleIds
            )) {
                var job = new LinkedHashMap<>(source);
                UUID id = uuid(job.get("id"));
                var prior = retrieved.get(id);
                if (prior == null || number(job.get("vector_score"), -1) > number(prior.get("vector_score"), -1)) {
                    job.put("document_index", i);
                    retrieved.put(id, job);
                }
            }
        }
        boolean vectorUsed = !retrieved.isEmpty();
        if (!vectorUsed && !"PROVIDER_ERROR".equals(semanticStatus)) {
            semanticStatus = number(catalog.get("active_jobs"), 0) == 0
                ? "EMPTY_CATALOG"
                : candidateVectors.isEmpty() ? semanticStatus : "NO_ELIGIBLE_VECTOR_RESULTS";
            if (fallbackReason.isBlank())
                fallbackReason = "No compatible vectors satisfy active-job and salary filters";
        }
        if (!vectorUsed || retrieved.size() < number(salaryPolicy.get("eligible_jobs"), 0)) {
            for (var source : jobs.fallbackCandidates(eligibleIds)) {
                UUID id = uuid(source.get("id"));
                retrieved.putIfAbsent(id, new LinkedHashMap<>(source));
            }
        }
        if (vectorUsed && number(catalog.get("compatible_vectors"), 0) < number(catalog.get("active_jobs"), 0)) {
            semanticStatus = "PARTIAL_INDEX";
        }
        timings.put("retrieval_ms", elapsed(stage));
        stage = System.nanoTime();
        List<Map<String, Object>> ranked = new ArrayList<>();
        int invalidJobs = 0;
        for (var job : retrieved.values()) {
            try {
                int index = (int) number(job.get("document_index"), 0);
                var assessment = RecommendationRules.assess(documents.get(index), job);
                if (job.get("vector_score") == null) {
                    for (int i = 1; i < documents.size(); i++) {
                        var alternative = RecommendationRules.assess(documents.get(i), job);
                        if (number(alternative.get("score"), 0) > number(assessment.get("score"), 0)) {
                            index = i;
                            assessment = alternative;
                        }
                    }
                }
                // The legacy rule eligibility flag is not an instruction to skip LLM scoring.
                assessment.remove("eligible_for_ai");
                job.put("document_index", index);
                job.put("source_resume_ids", documents.get(index).resumeIds());
                job.put("rule_assessment", assessment);
                job.put("rule_score", assessment.get("score"));
                job.put("final_score", assessment.get("score"));
                ranked.add(job);
            } catch (IllegalArgumentException failure) {
                invalidJobs++;
                LOG.warn(
                    "recommendation invalid_job={} failure_type={}",
                    job.get("id"),
                    failure.getClass().getSimpleName()
                );
            }
        }
        ranked.sort(RecommendationRules.ranking());
        timings.put("rules_ms", elapsed(stage));
        stage = System.nanoTime();
        var rerank = rerank(ranked, retrieved, documents);
        timings.put("rerank_ms", elapsed(stage));
        if (Boolean.TRUE.equals(rerank.get("failed")) || "INSUFFICIENT_EVIDENCE".equals(rerank.get("status")))
            cacheable = false;
        ranked.forEach(RecommendationScores::apply);
        ranked.removeIf(job -> !RecommendationScores.hasScore(job));
        ranked.sort(RecommendationScores.ranking());
        timings.put("total_ms", elapsed(started));
        var metadata = fields(
            "version",
            VERSION,
            "semantic_status",
            semanticStatus,
            "vector_used",
            vectorUsed,
            "fallback_reason",
            fallbackReason,
            "embedding_provider",
            provider,
            "embedding_model",
            model,
            "embedding_dimensions",
            dimensions,
            "active_jobs",
            catalog.get("active_jobs"),
            "compatible_vectors",
            catalog.get("compatible_vectors"),
            "legacy_input_vectors",
            catalog.get("legacy_input_vectors"),
            "retrieved_jobs",
            retrieved.size(),
            "ranked_jobs",
            ranked.size(),
            "invalid_jobs",
            invalidJobs,
            "candidate_documents",
            documents.size(),
            "embedding_calls",
            embeddingCalls,
            "rerank",
            rerank,
            "cacheable",
            cacheable,
            "timings_ms",
            timings,
            "ranking_policy",
            "ai_score_desc,id",
            "score_kind",
            RecommendationScores.KIND
        );
        UUID runId = matching.insertJobMatchingRuns(
            fields(
                "user_id",
                user.id(),
                "basis",
                basis,
                "profile_snapshot",
                matching.json(
                    fields(
                        "request_hash",
                        hash,
                        "mode",
                        "RECOMMENDATION",
                        "recommendation",
                        metadata,
                        "salary_filter",
                        salaryPolicy
                    )
                ),
                "provider",
                vectorUsed ? provider : "RULE_BASED",
                "model",
                VERSION,
                "status",
                "PROCESSING",
                "started_at",
                OffsetDateTime.now()
            )
        );
        cvs.forEach(cv -> matching.updateMatchingRunResumes(runId, uuid(cv.get("id"))));
        for (int i = 0; i < Math.min(resultLimit, ranked.size()); i++) {
            var job = ranked.get(i);
            var assessment = RecommendationScores.assessment(job);
            var aiAssessment = object(job.get("ai_assessment"));
            matching.insertJobMatchResults(
                fields(
                    "matching_run_id",
                    runId,
                    "market_job_id",
                    job.get("id"),
                    "vector_similarity_score",
                    job.get("vector_score"),
                    "llm_rerank_score",
                    job.get("advisory_score"),
                    "final_score",
                    job.get("final_score"),
                    "rank_position",
                    i + 1,
                    "match_reason_json",
                    matching.json(
                        fields(
                            "method",
                            "LLM_SUITABILITY_RECOMMENDATION",
                            "score_source",
                            "LLM",
                            "score_status",
                            job.get("ai_status"),
                            "retrieval",
                            job.get("vector_score") != null ? "PGVECTOR_COSINE" : "RULE_FALLBACK",
                            "reason",
                            aiAssessment.get("reason"),
                            "assessment",
                            assessment,
                            "ai_assessment",
                            job.get("ai_assessment"),
                            "source_resume_ids",
                            job.get("source_resume_ids"),
                            "salary_match",
                            salaryMatches.get(uuid(job.get("id")))
                        )
                    ),
                    "strengths_json",
                    matching.json(array(aiAssessment.get("strengths"))),
                    "skill_gaps_json",
                    matching.json(array(aiAssessment.get("skill_gaps")))
                )
            );
        }
        matching.updateJobMatchingRunsById(runId);
        if (cacheable)
            cache.putAfterCommit(
                cacheKey,
                identity,
                fields("id", runId.toString()),
                Duration.ofSeconds(bounded(cacheSeconds, 1, 3600))
            );
        LOG.info(
            ("recommendation run={} semantic={} vector_used={} active={} "
                + "compatible={} retrieved={} ranked={} rerank={} timings={}"),
            runId,
            semanticStatus,
            vectorUsed,
            catalog.get("active_jobs"),
            catalog.get("compatible_vectors"),
            retrieved.size(),
            ranked.size(),
            rerank,
            timings
        );
        return result(runId, false);
    }

    private Map<String, Object> rerank(
        List<Map<String, Object>> ranked,
        Map<UUID, Map<String, Object>> candidates,
        List<CandidateSearchProfile.Document> documents) {
        if (!rerankEnabled || !ai.enabled()) {
            ranked.forEach(job -> job.put("ai_status", "DISABLED"));
            return fields("status", "SKIPPED", "reason", "DISABLED", "calls", 0, "assessed_jobs", 0);
        }
        Map<Integer, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        ranked.forEach(
            job -> groups.computeIfAbsent((int) number(job.get("document_index"), 0), ignored -> new ArrayList<>())
                .add(job)
        );
        int calls = 0, assessed = 0, inputTokens = 0, outputTokens = 0;
        ApiException scoringFailure = null;
        String failureSource = null;
        String failureCode = null;
        assessmentGroups : for (var group : groups.entrySet()) {
            var document = documents.get(group.getKey());
            String fullEvidence = CandidateSearchProfile.factualEvidence(document);
            String evidence = excerpt(fullEvidence, 8000);
            for (var batch : JobMatchRerank.recommendationBatches(group.getValue())) {
                calls++;
                boolean modelReturned = false;
                try {
                    var generated = ai.generate(
                        "JOB_MATCH_RERANK",
                        fields(
                            "recommendation_mode",
                            true,
                            "candidate",
                            fields(
                                "desired_job_title",
                                excerpt(text(document.profile().get("desired_job_title")), 255),
                                "skills",
                                document.evidence().skills().stream().limit(50).toList(),
                                "years_experience",
                                document.evidence().yearsExperience(),
                                "preferences",
                                document.profile().get("preferences")
                            ),
                            "candidate_evidence",
                            evidence,
                            "candidate_evidence_truncated",
                            fullEvidence.length() > evidence.length(),
                            "jobs",
                            batch
                        )
                    );
                    modelReturned = true;
                    var usage = object(generated.get("_usage"));
                    inputTokens += (int) number(usage.get("input_tokens"), 0);
                    outputTokens += (int) number(usage.get("output_tokens"), 0);
                    var validated = JobMatchRerank.validateRecommendations(batch, generated, evidence);
                    for (var entry : validated.entrySet()) {
                        var job = candidates.get(entry.getKey());
                        var assessment = entry.getValue();
                        job.put("ai_assessment", assessment);
                        job.put("ai_status", assessment.get("validation_status"));
                        if (Boolean.TRUE.equals(assessment.get("usable"))) {
                            job.put("advisory_score", assessment.get("score"));
                            assessed++;
                        }
                    }
                } catch (ApiException failure) {
                    if (!recoverable(failure))
                        throw failure;
                    scoringFailure = failure;
                    if (modelReturned) {
                        failureSource = "MODEL_OUTPUT";
                        failureCode = "AI_OUTPUT_INVALID";
                        continue;
                    }
                    failureSource = failure.status() == 429 ? "APPLICATION_QUOTA" : "PROVIDER";
                    failureCode = failure.status() == 429 ? "AI_QUOTA_EXCEEDED" : "AI_PROVIDER_ERROR";
                    break assessmentGroups;
                }
            }
        }
        var result = fields(
            "status",
            scoringFailure != null
                ? (assessed == 0 ? "SKIPPED" : "PARTIAL")
                : assessed == ranked.size() ? "COMPLETED" : "INSUFFICIENT_EVIDENCE",
            "calls",
            calls,
            "assessed_jobs",
            assessed,
            "selected_jobs",
            ranked.size(),
            "input_tokens",
            inputTokens,
            "output_tokens",
            outputTokens
        );
        if (scoringFailure != null) {
            result.put("reason", "HTTP_" + scoringFailure.status());
            result.put("failed", true);
            result.put("failure_source", failureSource);
            result.put("failure_code", failureCode);
            result.put(
                "failure_message",
                excerpt(Objects.toString(scoringFailure.getMessage(), "AI assessment failed"), 240)
            );
        }
        return result;
    }

    private Map<String, Object> result(
        UUID id,
        boolean cached) {
        var result = matching.getJobMatchingRunsById(id, user.id());
        result.put(
            "results",
            matching.listJobMatchResultsByMatchingRunId(id).stream()
                .filter(job -> "ACTIVE".equals(job.get("job_status")) && RecommendationScores.hasScore(job))
                .limit(MAX_RESULTS).toList()
        );
        var snapshot = object(result.get("profile_snapshot"));
        result.put("recommendation", snapshot.get("recommendation"));
        result.put("salary_filter", snapshot.get("salary_filter"));
        result.put("cached", cached);
        return result;
    }

    public static void validateVector(
        float[] vector,
        int dimensions) {
        if (vector == null || vector.length != dimensions)
            throw new ApiException(502, "Embedding dimensions do not match the configured database vector");
        double norm = 0;
        for (float value : vector) {
            if (!Float.isFinite(value))
                throw new ApiException(502, "Embedding contains non-finite values");
            norm += (double) value * value;
        }
        if (norm == 0)
            throw new ApiException(502, "Embedding is a zero vector");
    }

    private static boolean recoverable(
        ApiException failure) {
        return failure.status() == 429 || failure.status() >= 500;
    }

    private static long elapsed(
        long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private static int bounded(
        int value,
        int min,
        int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static String excerpt(
        String value,
        int max) {
        return value.substring(0, Math.min(value.length(), max));
    }
}
