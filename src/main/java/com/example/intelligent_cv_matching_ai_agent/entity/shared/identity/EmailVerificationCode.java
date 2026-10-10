package com.example.intelligent_cv_matching_ai_agent.entity.shared.identity;

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
 * Persistence model for the email_verification_codes table; foreign keys retain
 * their explicit UUID identifiers.
 */
@Entity
@Table(name = "email_verification_codes")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class EmailVerificationCode {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("user_id")
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @JsonProperty("code_hash")
    @JsonIgnore
    @Column(name = "code_hash", nullable = false, length = 255)
    private String codeHash;

    @JsonProperty("expires_at")
    @Column(name = "expires_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime expiresAt;

    @JsonProperty("verified_at")
    @Column(name = "verified_at", nullable = true, columnDefinition = "timestamptz")
    private OffsetDateTime verifiedAt;

    @JsonProperty("attempt_count")
    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

}
