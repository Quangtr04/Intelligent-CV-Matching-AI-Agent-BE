package com.example.intelligent_cv_matching_ai_agent.entity.candidate.resume;

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
 * Persistence model for the resume_analyses table; foreign keys retain their
 * explicit UUID identifiers.
 */
@Entity
@Table(name = "resume_analyses")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class ResumeAnalysis {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("resume_id")
    @Column(name = "resume_id", nullable = false)
    private UUID resumeId;

    @JsonProperty("analysis_type")
    @Column(name = "analysis_type", nullable = false, length = 50)
    private String analysisType;

    @JsonProperty("overall_score")
    @Column(name = "overall_score", nullable = true, precision = 5, scale = 2)
    private BigDecimal overallScore;

    @JsonProperty("ats_score")
    @Column(name = "ats_score", nullable = true, precision = 5, scale = 2)
    private BigDecimal atsScore;

    @JsonProperty("summary")
    @Column(name = "summary", nullable = true, columnDefinition = "text")
    private String summary;

    @JsonProperty("strengths_json")
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "strengths_json", nullable = true, columnDefinition = "jsonb")
    private Object strengthsJson;

    @JsonProperty("issues_json")
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "issues_json", nullable = true, columnDefinition = "jsonb")
    private Object issuesJson;

    @JsonProperty("model_name")
    @Column(name = "model_name", nullable = true, length = 150)
    private String modelName;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

}
