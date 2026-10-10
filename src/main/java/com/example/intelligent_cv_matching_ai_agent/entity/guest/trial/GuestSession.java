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
 * Persistence model for the guest_sessions table; foreign keys retain their
 * explicit UUID identifiers.
 */
@Entity
@Table(name = "guest_sessions")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class GuestSession {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("session_key")
    @JsonIgnore
    @Column(name = "session_key", nullable = false, length = 120)
    private String sessionKey;

    @JsonProperty("token_limit")
    @Column(name = "token_limit", nullable = false)
    private Integer tokenLimit;

    @JsonProperty("token_used")
    @Column(name = "token_used", nullable = false)
    private Integer tokenUsed;

    @JsonProperty("converted_user_id")
    @Column(name = "converted_user_id", nullable = true)
    private UUID convertedUserId;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

    @JsonProperty("expires_at")
    @Column(name = "expires_at", nullable = true, columnDefinition = "timestamptz")
    private OffsetDateTime expiresAt;

}
