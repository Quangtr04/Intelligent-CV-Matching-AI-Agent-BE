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

    public static final String VERSION = "recommendation-v6-vector-only";

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
        Optional<SalaryExpectation> salary =
            basis.equals("CAREER_PROFILE") ? SalaryExpectation.fromProfile(profile, settings) : Optional.empty();
        if (salary.isPresent() && rates != null)
            salary = SalaryExpectation.fromProfile(profile, settings, rates.snapshot());
        Map<UUID, Map<String, Object>> salaryMatches = new HashMap<>();
        Set<UUID> eligible = null;
        if (salary.isPresent()) {
            eligible = new LinkedHashSet<>();
            for (var job : matching.listActiveJobSalaryMetadata()) {
                var assessment = salary.get().matchRange(text(job.get("salary_text")));
                if (assessment.isPresent()) {
                    UUID id = uuid(job.get("id"));
                    eligible.add(id);
                    salaryMatches.put(id, assessment.get());
                }
            }
        }
        var salaryPolicy = salary.map(SalaryExpectation::rangePolicy).orElse(fields("active", false));
        salaryPolicy.put("filter_applied", salary.isPresent());
        salaryPolicy.put("eligible_jobs", eligible == null ? catalog.get("active_jobs") : eligible.size());
        String hash = FlowSupport.hash(
            VERSION + "\n" + CandidateSearchProfile.VERSION + "\n" + SkillCatalog.get().version() + "\n"
                + JobEmbeddingSupport.VERSION + "\n" + basis + "\n"
                + JSON.writeValueAsString(
                    documents.stream().map(doc -> fields("hash", doc.contentHash(), "resumes", doc.resumeIds()))
                        .toList()
                ) + "\n" + provider + "\n" + model + "\n" + dimensions + "\n" + salaryPolicy + "\n"
                + salary.map(SalaryExpectation::exchangeRates) + "\n" + matching.readMarketJobPostsByStatus() + "\n"
                + catalog + "\n" + resultLimit + ":" + ai.embeddingsEnabled()
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
                String vectorHash =
                    FlowSupport.hash(CANDIDATE_VECTOR_CACHE_VERSION + "\n" + dimensions + "\n" + document.searchText());
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
        if (vectorUsed && number(catalog.get("compatible_vectors"), 0) < number(catalog.get("active_jobs"), 0)) {
            semanticStatus = "PARTIAL_INDEX";
        }
        timings.put("retrieval_ms", elapsed(stage));
        List<Map<String, Object>> ranked = new ArrayList<>(retrieved.values());
        for (var job : ranked) {
            int index = (int) number(job.get("document_index"), 0);
            job.put("source_resume_ids", documents.get(index).resumeIds());
            RecommendationScores.apply(job);
        }
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
            retrieved.size() - ranked.size(),
            "candidate_documents",
            documents.size(),
            "embedding_calls",
            embeddingCalls,
            "cacheable",
            cacheable,
            "timings_ms",
            timings,
            "ranking_policy",
            "vector_similarity_desc,id",
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
                provider,
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
            matching.insertJobMatchResults(
                fields(
                    "matching_run_id",
                    runId,
                    "market_job_id",
                    job.get("id"),
                    "vector_similarity_score",
                    job.get("vector_score"),
                    "llm_rerank_score",
                    null,
                    "final_score",
                    job.get("final_score"),
                    "rank_position",
                    i + 1,
                    "match_reason_json",
                    matching.json(
                        fields(
                            "method",
                            "SIMILARITY_RECOMMENDATION",
                            "score_source",
                            "PGVECTOR_COSINE",
                            "score_status",
                            "SCORED",
                            "retrieval",
                            "PGVECTOR_COSINE",
                            "assessment",
                            assessment,
                            "source_resume_ids",
                            job.get("source_resume_ids"),
                            "salary_match",
                            salaryMatches.get(uuid(job.get("id")))
                        )
                    ),
                    "strengths_json",
                    matching.json(List.of()),
                    "skill_gaps_json",
                    matching.json(List.of())
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
                + "compatible={} retrieved={} ranked={} timings={}"),
            runId,
            semanticStatus,
            vectorUsed,
            catalog.get("active_jobs"),
            catalog.get("compatible_vectors"),
            retrieved.size(),
            ranked.size(),
            timings
        );
        return result(runId, false);
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
}
