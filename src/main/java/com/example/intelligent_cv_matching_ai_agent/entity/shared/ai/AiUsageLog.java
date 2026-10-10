package com.example.intelligent_cv_matching_ai_agent.entity.shared.ai;

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
 * Persistence model for the ai_usage_logs table; foreign keys retain their
 * explicit UUID identifiers.
 */
@Entity
@Table(name = "ai_usage_logs")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class AiUsageLog {

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

    @JsonProperty("subscription_id")
    @Column(name = "subscription_id", nullable = true)
    private UUID subscriptionId;

    @JsonProperty("feature_code")
    @Column(name = "feature_code", nullable = false, length = 100)
    private String featureCode;

    @JsonProperty("provider")
    @Column(name = "provider", nullable = true, length = 100)
    private String provider;

    @JsonProperty("model_name")
    @Column(name = "model_name", nullable = true, length = 150)
    private String modelName;

    @JsonProperty("input_tokens")
    @Column(name = "input_tokens", nullable = false)
    private Integer inputTokens;

    @JsonProperty("output_tokens")
    @Column(name = "output_tokens", nullable = false)
    private Integer outputTokens;

    @JsonProperty("total_tokens")
    @Column(name = "total_tokens", nullable = false)
    private Integer totalTokens;

    @JsonProperty("estimated_cost")
    @Column(name = "estimated_cost", nullable = true, precision = 14, scale = 6)
    private BigDecimal estimatedCost;

    @JsonProperty("latency_ms")
    @Column(name = "latency_ms", nullable = true)
    private Long latencyMs;

    @JsonProperty("success")
    @Column(name = "success", nullable = false)
    private Boolean success;

    @JsonProperty("error_message")
    @Column(name = "error_message", nullable = true, columnDefinition = "text")
    private String errorMessage;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

}
