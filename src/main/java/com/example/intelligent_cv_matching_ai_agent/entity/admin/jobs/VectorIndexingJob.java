package com.example.intelligent_cv_matching_ai_agent.entity.admin.jobs;

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
 * Persistence model for the vector_indexing_jobs table; foreign keys retain
 * their explicit UUID identifiers.
 */
@Entity
@Table(name = "vector_indexing_jobs")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class VectorIndexingJob {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("entity_type")
    @Column(name = "entity_type", nullable = false, length = 30)
    private String entityType;

    @JsonProperty("entity_id")
    @Column(name = "entity_id", nullable = true)
    private UUID entityId;

    @JsonProperty("operation")
    @Column(name = "operation", nullable = false, length = 30)
    private String operation;

    @JsonProperty("provider")
    @Column(name = "provider", nullable = true, length = 50)
    private String provider;

    @JsonProperty("model")
    @Column(name = "model", nullable = true, length = 100)
    private String model;

    @JsonProperty("status")
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @JsonProperty("attempt_count")
    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @JsonProperty("error_message")
    @Column(name = "error_message", nullable = true, columnDefinition = "text")
    private String errorMessage;

    @JsonProperty("started_at")
    @Column(name = "started_at", nullable = true, columnDefinition = "timestamptz")
    private OffsetDateTime startedAt;

    @JsonProperty("completed_at")
    @Column(name = "completed_at", nullable = true, columnDefinition = "timestamptz")
    private OffsetDateTime completedAt;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

}
