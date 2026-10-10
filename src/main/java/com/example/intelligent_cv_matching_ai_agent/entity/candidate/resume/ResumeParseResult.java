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
 * Persistence model for the resume_parse_results table; foreign keys retain
 * their explicit UUID identifiers.
 */
@Entity
@Table(name = "resume_parse_results")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class ResumeParseResult {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("resume_id")
    @Column(name = "resume_id", nullable = false)
    private UUID resumeId;

    @JsonProperty("parser_name")
    @Column(name = "parser_name", nullable = true, length = 100)
    private String parserName;

    @JsonProperty("parser_version")
    @Column(name = "parser_version", nullable = true, length = 50)
    private String parserVersion;

    @JsonProperty("ocr_used")
    @Column(name = "ocr_used", nullable = false)
    private Boolean ocrUsed;

    @JsonProperty("raw_text")
    @Column(name = "raw_text", nullable = true, columnDefinition = "text")
    private String rawText;

    @JsonProperty("extracted_data")
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extracted_data", nullable = true, columnDefinition = "jsonb")
    private Object extractedData;

    @JsonProperty("normalized_data")
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "normalized_data", nullable = true, columnDefinition = "jsonb")
    private Object normalizedData;

    @JsonProperty("confidence_score")
    @Column(name = "confidence_score", nullable = true, precision = 5, scale = 2)
    private BigDecimal confidenceScore;

    @JsonProperty("status")
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @JsonProperty("error_message")
    @Column(name = "error_message", nullable = true, columnDefinition = "text")
    private String errorMessage;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

}
