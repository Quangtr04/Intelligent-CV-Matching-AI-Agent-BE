package com.example.intelligent_cv_matching_ai_agent.repository.candidate.jobs;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobEmbeddingSupport;
import java.util.*;
import org.springframework.stereotype.Repository;

/** Cosine comparisons run in PostgreSQL without transferring job embeddings to Java. */
@Repository
public class RecommendationRepository {

    private static final String COLUMNS = ("j.id,j.job_title,j.company_name,j.location,j.salary_text,"
        + "j.employment_type,j.role_category,j.valid_through,j.raw_content_hash,"
        + "j.raw_content,j.original_content,j.parsed_description,"
        + "j.parsed_requirements,j.parsed_responsibilities,j.parsed_seniority,"
        + "j.requirement_extraction,j.requirement_extraction_verified,"
        + "COALESCE((SELECT jsonb_agg(jsonb_build_object('name',"
        + "s.normalized_skill,'required',s.is_required) ORDER BY "
        + "s.normalized_skill) FROM market_job_skills s WHERE " + "s.market_job_id=j.id),'[]'::jsonb) AS skills");

    private static final String ACTIVE = "j.status='ACTIVE' AND (j.valid_through IS NULL OR j.valid_through>now())";

    private final SqlStore db;

    public RecommendationRepository(
        SqlStore db) {
        this.db = db;
    }

    public Map<String, Object> vectorCatalog(
        String provider,
        String model,
        int dimensions) {
        return db.one(
            ("SELECT count(*) AS active_jobs,count(e.id) FILTER(WHERE e.input_version=?) AS compatible_vectors,"
                + "count(e.id) FILTER(WHERE e.input_version='legacy-full-v1') AS " + "legacy_input_vectors,"
                + "md5(COALESCE(string_agg(e.id::text||':'||e.content_hash||':'||e.input_"
                + "version||':'||e.created_at::text,',' ORDER BY e.id) FILTER(WHERE e.input_version=?),'')) AS "
                + "vector_fingerprint FROM market_job_posts j LEFT JOIN "
                + "market_job_embeddings e ON e.market_job_id=j.id AND e.is_current AND "
                + "e.embedding IS NOT NULL AND e.provider=? AND e.model=? AND "
                + "vector_dims(e.embedding)=? AND COALESCE(e.source_content_hash,"
                + "e.content_hash)=j.raw_content_hash WHERE ") + ACTIVE,
            JobEmbeddingSupport.VERSION,
            JobEmbeddingSupport.VERSION,
            provider,
            model,
            dimensions
        );
    }

    public List<Map<String, Object>> semanticCandidates(
        String provider,
        String model,
        int dimensions,
        String vector,
        String eligibleIds) {
        String filter = eligibleIds == null ? "" : " AND j.id=ANY(CAST(? AS uuid[]))";
        List<Object> parameters =
            new ArrayList<>(List.of(vector, provider, model, dimensions, JobEmbeddingSupport.VERSION));
        if (eligibleIds != null)
            parameters.add(eligibleIds);
        return db.list(
            "SELECT " + COLUMNS + ",1-(e.embedding <=> CAST(? AS vector)) AS vector_score,"
                + "e.input_version AS embedding_input_version FROM market_job_embeddings e "
                + "JOIN market_job_posts j ON j.id=e.market_job_id WHERE " + ACTIVE
                + " AND e.is_current AND e.embedding IS NOT NULL AND e.provider=? AND e.model=? "
                + ("AND vector_dims(e.embedding)=? AND e.input_version=? " + "AND COALESCE(e.source_content_hash,"
                    + "e.content_hash)=j.raw_content_hash")
                + filter,
            parameters.toArray()
        );
    }

    public Optional<Map<String, Object>> cachedRun(
        UUID owner,
        String hash,
        int ttlSeconds) {
        return db.optional(
            ("SELECT id FROM job_matching_runs WHERE user_id=? AND "
                + "status='COMPLETED' AND profile_snapshot->>'mode'='RECOMMENDATION' AND "
                + "profile_snapshot->>'request_hash'=? AND "
                + "profile_snapshot->'recommendation'->>'cacheable'='true' AND "
                + "completed_at>now()-make_interval(secs=>?) ORDER BY completed_at DESC " + "LIMIT 1"),
            owner,
            hash,
            ttlSeconds
        );
    }
}
