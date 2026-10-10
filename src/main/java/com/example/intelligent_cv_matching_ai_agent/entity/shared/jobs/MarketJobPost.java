package com.example.intelligent_cv_matching_ai_agent.entity.shared.jobs;

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
 * Persistence model for the market_job_posts table; foreign keys retain their
 * explicit UUID identifiers.
 */
@Entity
@Table(name = "market_job_posts")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class MarketJobPost {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("source_site")
    @Column(name = "source_site", nullable = false, length = 50)
    private String sourceSite;

    @JsonProperty("source_url")
    @Column(name = "source_url", nullable = true, columnDefinition = "text")
    private String sourceUrl;

    @JsonProperty("raw_content_hash")
    @Column(name = "raw_content_hash", nullable = false, length = 64)
    private String rawContentHash;

    @JsonProperty("job_title")
    @Column(name = "job_title", nullable = false, length = 255)
    private String jobTitle;

    @JsonProperty("company_name")
    @Column(name = "company_name", nullable = true, length = 255)
    private String companyName;

    @JsonProperty("location")
    @Column(name = "location", nullable = true, length = 255)
    private String location;

    @JsonProperty("salary_text")
    @Column(name = "salary_text", nullable = true, length = 255)
    private String salaryText;

    @JsonProperty("role_category")
    @Column(name = "role_category", nullable = false, length = 100)
    private String roleCategory;

    @JsonProperty("raw_content")
    @Column(name = "raw_content", nullable = false, columnDefinition = "text")
    private String rawContent;

    @JsonProperty("original_content")
    @Column(name = "original_content", nullable = true, columnDefinition = "text")
    private String originalContent;

    @JsonProperty("original_content_format")
    @Column(name = "original_content_format", nullable = false, length = 32)
    private String originalContentFormat;

    @JsonProperty("original_content_quality")
    @Column(name = "original_content_quality", nullable = false, length = 32)
    private String originalContentQuality;

    @JsonProperty("original_content_extracted_at")
    @Column(name = "original_content_extracted_at", nullable = true, columnDefinition = "timestamptz")
    private OffsetDateTime originalContentExtractedAt;

    @JsonProperty("parsed_description")
    @Column(name = "parsed_description", nullable = true, columnDefinition = "text")
    private String parsedDescription;

    @JsonProperty("parsed_requirements")
    @Column(name = "parsed_requirements", nullable = true, columnDefinition = "text")
    private String parsedRequirements;

    @JsonProperty("parsed_responsibilities")
    @Column(name = "parsed_responsibilities", nullable = true, columnDefinition = "text")
    private String parsedResponsibilities;

    @JsonProperty("parsed_benefits")
    @Column(name = "parsed_benefits", nullable = true, columnDefinition = "text")
    private String parsedBenefits;

    @JsonProperty("parsed_company_overview")
    @Column(name = "parsed_company_overview", nullable = true, columnDefinition = "text")
    private String parsedCompanyOverview;

    @JsonProperty("parsed_seniority")
    @Column(name = "parsed_seniority", nullable = true, length = 64)
    private String parsedSeniority;

    @JsonProperty("parsed_confidence")
    @Column(name = "parsed_confidence", nullable = true, precision = 5, scale = 2)
    private BigDecimal parsedConfidence;

    @JsonProperty("parsed_at")
    @Column(name = "parsed_at", nullable = true, columnDefinition = "timestamptz")
    private OffsetDateTime parsedAt;

    @JsonProperty("posted_at")
    @Column(name = "posted_at", nullable = true, columnDefinition = "timestamptz")
    private OffsetDateTime postedAt;

    @JsonProperty("collected_at")
    @Column(name = "collected_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime collectedAt;

    @JsonProperty("status")
    @Column(name = "status", nullable = false, length = 32)
    private String status;

}
