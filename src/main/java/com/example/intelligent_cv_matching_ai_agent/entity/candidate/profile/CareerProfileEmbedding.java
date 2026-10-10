package com.example.intelligent_cv_matching_ai_agent.entity.candidate.profile;

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
 * Persistence model for the career_profile_embeddings table; foreign keys
 * retain their explicit UUID identifiers.
 */
@Entity
@Table(name = "career_profile_embeddings")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class CareerProfileEmbedding {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("user_id")
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @JsonProperty("provider")
    @Column(name = "provider", nullable = false, length = 30)
    private String provider;

    @JsonProperty("model")
    @Column(name = "model", nullable = false, length = 80)
    private String model;

    @JsonProperty("embedding")
    @ColumnTransformer(read = "embedding::text", write = "?::vector")
    @Column(name = "embedding", nullable = true, columnDefinition = "vector(1536)")
    private String embedding;

    @JsonProperty("content_hash")
    @Column(name = "content_hash", nullable = true, length = 64)
    private String contentHash;

    @JsonProperty("is_current")
    @Column(name = "is_current", nullable = false)
    private Boolean isCurrent;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

}
