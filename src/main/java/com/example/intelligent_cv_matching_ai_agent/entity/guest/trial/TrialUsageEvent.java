package com.example.intelligent_cv_matching_ai_agent.entity.guest.trial;

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
 * Persistence model for the trial_usage_events table; foreign keys retain their
 * explicit UUID identifiers.
 */
@Entity
@Table(name = "trial_usage_events")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class TrialUsageEvent {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("guest_session_id")
    @Column(name = "guest_session_id", nullable = false)
    private UUID guestSessionId;

    @JsonProperty("feature_code")
    @Column(name = "feature_code", nullable = false, length = 100)
    private String featureCode;

    @JsonProperty("demo_resume_id")
    @Column(name = "demo_resume_id", nullable = true)
    private UUID demoResumeId;

    @JsonProperty("tokens_used")
    @Column(name = "tokens_used", nullable = false)
    private Integer tokensUsed;

    @JsonProperty("status")
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

}
