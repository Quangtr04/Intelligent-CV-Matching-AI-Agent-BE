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
 * Persistence model for the ai_token_reservations table; foreign keys retain
 * their explicit UUID identifiers.
 */
@Entity
@Table(name = "ai_token_reservations")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class AiTokenReservation {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("user_id")
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @JsonProperty("feature_code")
    @Column(name = "feature_code", nullable = false, length = 100)
    private String featureCode;

    @JsonProperty("reserved_tokens")
    @Column(name = "reserved_tokens", nullable = false)
    private Long reservedTokens;

    @JsonProperty("consumed_tokens")
    @Column(name = "consumed_tokens", nullable = false)
    private Long consumedTokens;

    @JsonProperty("status")
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

}
