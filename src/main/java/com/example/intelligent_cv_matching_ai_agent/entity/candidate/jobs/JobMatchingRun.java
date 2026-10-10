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
 * Persistence model for the job_matching_runs table; foreign keys retain their
 * explicit UUID identifiers.
 */
@Entity
@Table(name = "job_matching_runs")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class JobMatchingRun {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("user_id")
    @Column(name = "user_id", nullable = true)
    private UUID userId;

    @JsonProperty("guest_session_id")
    @Column(name = "guest_session_id", nullable = true)
    private UUID guestSessionId;

    @JsonProperty("basis")
    @Column(name = "basis", nullable = false, length = 32)
    private String basis;

    @JsonProperty("profile_snapshot")
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "profile_snapshot", nullable = true, columnDefinition = "jsonb")
    private Object profileSnapshot;

    @JsonProperty("provider")
    @Column(name = "provider", nullable = true, length = 30)
    private String provider;

    @JsonProperty("model")
    @Column(name = "model", nullable = true, length = 80)
    private String model;

    @JsonProperty("status")
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @JsonProperty("started_at")
    @Column(name = "started_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime startedAt;

    @JsonProperty("completed_at")
    @Column(name = "completed_at", nullable = true, columnDefinition = "timestamptz")
    private OffsetDateTime completedAt;

}
