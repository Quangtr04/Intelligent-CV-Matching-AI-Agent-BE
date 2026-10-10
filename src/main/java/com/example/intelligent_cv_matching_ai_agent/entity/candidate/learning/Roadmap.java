package com.example.intelligent_cv_matching_ai_agent.entity.candidate.learning;

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
 * Persistence model for the roadmaps table; foreign keys retain their explicit
 * UUID identifiers.
 */
@Entity
@Table(name = "roadmaps")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class Roadmap {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("user_id")
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @JsonProperty("market_job_id")
    @Column(name = "market_job_id", nullable = true)
    private UUID marketJobId;

    @JsonProperty("gap_analysis_id")
    @Column(name = "gap_analysis_id", nullable = true)
    private UUID gapAnalysisId;

    @JsonProperty("target_job_title")
    @Column(name = "target_job_title", nullable = true, length = 255)
    private String targetJobTitle;

    @JsonProperty("title")
    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @JsonProperty("estimated_total_hours")
    @Column(name = "estimated_total_hours", nullable = true)
    private Integer estimatedTotalHours;

    @JsonProperty("status")
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @JsonProperty("is_outdated")
    @Column(name = "is_outdated", nullable = false)
    private Boolean isOutdated;

    @JsonProperty("progress_percent")
    @Column(name = "progress_percent", nullable = false)
    private Short progressPercent;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

    @JsonProperty("updated_at")
    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime updatedAt;

}
