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
 * Persistence model for the assessment_sessions table; foreign keys retain
 * their explicit UUID identifiers.
 */
@Entity
@Table(name = "assessment_sessions")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class AssessmentSession {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("user_id")
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @JsonProperty("target_job_title")
    @Column(name = "target_job_title", nullable = true, length = 255)
    private String targetJobTitle;

    @JsonProperty("market_job_id")
    @Column(name = "market_job_id", nullable = true)
    private UUID marketJobId;

    @JsonProperty("resume_id")
    @Column(name = "resume_id")
    private UUID resumeId;

    @JsonProperty("input_hash")
    @Column(name = "input_hash", length = 64)
    private String inputHash;

    @JsonIgnore
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context_snapshot", columnDefinition = "jsonb")
    private Object contextSnapshot;

    @JsonProperty("job_role_category_snapshot")
    @Column(name = "job_role_category_snapshot", nullable = true, length = 100)
    private String jobRoleCategorySnapshot;

    @JsonProperty("part1_count")
    @Column(name = "part1_count", nullable = true)
    private Short part1Count;

    @JsonProperty("part2_count")
    @Column(name = "part2_count", nullable = true)
    private Short part2Count;

    @JsonProperty("skill_scores")
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "skill_scores", nullable = true, columnDefinition = "jsonb")
    private Object skillScores;

    @JsonProperty("status")
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @JsonProperty("is_current")
    @Column(name = "is_current", nullable = false)
    private Boolean isCurrent;

    @JsonProperty("reused_from_session_id")
    @Column(name = "reused_from_session_id", nullable = true)
    private UUID reusedFromSessionId;

    @JsonProperty("started_at")
    @Column(name = "started_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime startedAt;

    @JsonProperty("submitted_at")
    @Column(name = "submitted_at", nullable = true, columnDefinition = "timestamptz")
    private OffsetDateTime submittedAt;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

}
