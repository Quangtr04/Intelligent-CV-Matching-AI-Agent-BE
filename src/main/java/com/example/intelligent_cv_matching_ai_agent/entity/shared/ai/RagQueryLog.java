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
 * Persistence model for the rag_query_logs table; foreign keys retain their
 * explicit UUID identifiers.
 */
@Entity
@Table(name = "rag_query_logs")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class RagQueryLog {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("user_id")
    @Column(name = "user_id", nullable = true)
    private UUID userId;

    @JsonProperty("query_type")
    @Column(name = "query_type", nullable = false, length = 50)
    private String queryType;

    @JsonProperty("entity_id")
    @Column(name = "entity_id", nullable = true)
    private UUID entityId;

    @JsonProperty("prompt_tokens")
    @Column(name = "prompt_tokens", nullable = false)
    private Integer promptTokens;

    @JsonProperty("completion_tokens")
    @Column(name = "completion_tokens", nullable = false)
    private Integer completionTokens;

    @JsonProperty("cost_usd")
    @Column(name = "cost_usd", nullable = false, precision = 10, scale = 6)
    private BigDecimal costUsd;

    @JsonProperty("duration_ms")
    @Column(name = "duration_ms", nullable = false)
    private Integer durationMs;

    @JsonProperty("model_used")
    @Column(name = "model_used", nullable = false, length = 50)
    private String modelUsed;

    @JsonProperty("success")
    @Column(name = "success", nullable = false)
    private Boolean success;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

}
