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
 * Persistence model for the external_apply_events table; foreign keys retain
 * their explicit UUID identifiers.
 */
@Entity
@Table(name = "external_apply_events")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class ExternalApplyEvent {

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
    @Column(name = "market_job_id", nullable = false)
    private UUID marketJobId;

    @JsonProperty("resume_id")
    @Column(name = "resume_id", nullable = true)
    private UUID resumeId;

    @JsonProperty("event_type")
    @Column(name = "event_type", nullable = false, length = 30)
    private String eventType;

    @JsonProperty("target_url")
    @Column(name = "target_url", nullable = true, columnDefinition = "text")
    private String targetUrl;

    @JsonProperty("occurred_at")
    @Column(name = "occurred_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime occurredAt;

}
