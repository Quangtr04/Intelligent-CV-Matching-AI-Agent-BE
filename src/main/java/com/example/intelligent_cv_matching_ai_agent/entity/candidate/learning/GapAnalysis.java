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
 * Persistence model for the gap_analyses table; foreign keys retain their
 * explicit UUID identifiers.
 */
@Entity
@Table(name = "gap_analyses")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class GapAnalysis {

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

    @JsonProperty("resume_id")
    @Column(name = "resume_id", nullable = true)
    private UUID resumeId;

    @JsonProperty("assessment_session_id")
    @Column(name = "assessment_session_id", nullable = true)
    private UUID assessmentSessionId;

    @JsonProperty("target_job_title")
    @Column(name = "target_job_title", nullable = true, length = 255)
    private String targetJobTitle;

    @JsonProperty("input_source")
    @Column(name = "input_source", nullable = false, length = 30)
    private String inputSource;

    @JsonProperty("context_key")
    @Column(name = "context_key", length = 100)
    private String contextKey;

    @JsonProperty("input_hash")
    @Column(name = "input_hash", length = 64)
    private String inputHash;

    @JsonIgnore
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context_snapshot", columnDefinition = "jsonb")
    private Object contextSnapshot;

    @JsonProperty("version")
    @Column(name = "version", nullable = false)
    private Short version;

    @JsonProperty("is_latest")
    @Column(name = "is_latest", nullable = false)
    private Boolean isLatest;

    @JsonProperty("summary")
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "summary", nullable = true, columnDefinition = "jsonb")
    private Object summary;

    @JsonProperty("status")
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @JsonProperty("error")
    @Column(name = "error", nullable = true, columnDefinition = "text")
    private String error;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

    @JsonProperty("completed_at")
    @Column(name = "completed_at", nullable = true, columnDefinition = "timestamptz")
    private OffsetDateTime completedAt;

}
