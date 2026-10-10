package com.example.intelligent_cv_matching_ai_agent.entity.candidate.jobs;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * Persistence model for the job_match_results table; foreign keys retain their
 * explicit UUID identifiers.
 */
@Entity
@Table(name = "job_match_results")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class JobMatchResult {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("matching_run_id")
    @Column(name = "matching_run_id", nullable = false)
    private UUID matchingRunId;

    @JsonProperty("market_job_id")
    @Column(name = "market_job_id", nullable = false)
    private UUID marketJobId;

    @JsonProperty("vector_similarity_score")
    @Column(name = "vector_similarity_score", nullable = true, precision = 6, scale = 5)
    private BigDecimal vectorSimilarityScore;

    @JsonProperty("llm_rerank_score")
    @Column(name = "llm_rerank_score", nullable = true, precision = 6, scale = 5)
    private BigDecimal llmRerankScore;

    @JsonProperty("final_score")
    @Column(name = "final_score", nullable = true, precision = 6, scale = 5)
    private BigDecimal finalScore;

    @JsonProperty("rank_position")
    @Column(name = "rank_position", nullable = true)
    private Integer rankPosition;

    @JsonProperty("match_reason_json")
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "match_reason_json", nullable = true, columnDefinition = "jsonb")
    private Object matchReasonJson;

    @JsonProperty("strengths_json")
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "strengths_json", nullable = true, columnDefinition = "jsonb")
    private Object strengthsJson;

    @JsonProperty("skill_gaps_json")
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "skill_gaps_json", nullable = true, columnDefinition = "jsonb")
    private Object skillGapsJson;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

}
