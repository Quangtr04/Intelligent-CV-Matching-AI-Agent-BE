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
 * Persistence model for the resume_analysis_suggestions table; foreign keys
 * retain their explicit UUID identifiers.
 */
@Entity
@Table(name = "resume_analysis_suggestions")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class ResumeAnalysisSuggestion {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("analysis_id")
    @Column(name = "analysis_id", nullable = false)
    private UUID analysisId;

    @JsonProperty("category")
    @Column(name = "category", nullable = true, length = 100)
    private String category;

    @JsonProperty("severity")
    @Column(name = "severity", nullable = true, length = 30)
    private String severity;

    @JsonProperty("original_content")
    @Column(name = "original_content", nullable = true, columnDefinition = "text")
    private String originalContent;

    @JsonProperty("suggested_content")
    @Column(name = "suggested_content", nullable = true, columnDefinition = "text")
    private String suggestedContent;

    @JsonProperty("explanation")
    @Column(name = "explanation", nullable = true, columnDefinition = "text")
    private String explanation;

    @JsonProperty("accepted")
    @Column(name = "accepted", nullable = true)
    private Boolean accepted;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

}
