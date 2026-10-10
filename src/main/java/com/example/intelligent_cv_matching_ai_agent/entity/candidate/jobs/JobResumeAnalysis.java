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
 * Persistence model for the job_resume_analyses table; foreign keys retain
 * their explicit UUID identifiers.
 */
@Entity
@Table(name = "job_resume_analyses")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class JobResumeAnalysis {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("user_id")
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @JsonProperty("resume_id")
    @Column(name = "resume_id", nullable = false)
    private UUID resumeId;

    @JsonProperty("market_job_id")
    @Column(name = "market_job_id", nullable = false)
    private UUID marketJobId;

    @JsonProperty("match_result_id")
    @Column(name = "match_result_id", nullable = true)
    private UUID matchResultId;

    @JsonProperty("similarity_score")
    @Column(name = "similarity_score", nullable = true, precision = 6, scale = 5)
    private BigDecimal similarityScore;

    @JsonProperty("ats_score")
    @Column(name = "ats_score", nullable = true, precision = 5, scale = 2)
    private BigDecimal atsScore;

    @JsonProperty("strengths_json")
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "strengths_json", nullable = true, columnDefinition = "jsonb")
    private Object strengthsJson;

    @JsonProperty("missing_skills_json")
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "missing_skills_json", nullable = true, columnDefinition = "jsonb")
    private Object missingSkillsJson;

    @JsonProperty("improvement_suggestions_json")
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "improvement_suggestions_json", nullable = true, columnDefinition = "jsonb")
    private Object improvementSuggestionsJson;

    @JsonProperty("model_name")
    @Column(name = "model_name", nullable = true, length = 150)
    private String modelName;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

}
