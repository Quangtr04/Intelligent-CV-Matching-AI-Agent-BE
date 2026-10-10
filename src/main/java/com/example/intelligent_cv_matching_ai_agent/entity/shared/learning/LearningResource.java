package com.example.intelligent_cv_matching_ai_agent.entity.shared.learning;

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
 * Persistence model for the learning_resources table; foreign keys retain their
 * explicit UUID identifiers.
 */
@Entity
@Table(name = "learning_resources")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class LearningResource {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("title")
    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @JsonProperty("resource_type")
    @Column(name = "resource_type", nullable = false, length = 30)
    private String resourceType;

    @JsonProperty("provider")
    @Column(name = "provider", nullable = true, length = 150)
    private String provider;

    @JsonProperty("url")
    @Column(name = "url", nullable = false, columnDefinition = "text")
    private String url;

    @JsonProperty("description")
    @Column(name = "description", nullable = true, columnDefinition = "text")
    private String description;

    @JsonProperty("language")
    @Column(name = "language", nullable = true, length = 10)
    private String language;

    @JsonProperty("duration_minutes")
    @Column(name = "duration_minutes", nullable = true)
    private Integer durationMinutes;

    @JsonProperty("is_free")
    @Column(name = "is_free", nullable = false)
    private Boolean isFree;

    @JsonProperty("source_type")
    @Column(name = "source_type", nullable = false, length = 30)
    private String sourceType;

    @JsonProperty("metadata_json")
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", nullable = true, columnDefinition = "jsonb")
    private Object metadataJson;

    @JsonProperty("is_active")
    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    @JsonProperty("discovered_at")
    @Column(name = "discovered_at", nullable = true, columnDefinition = "timestamptz")
    private OffsetDateTime discoveredAt;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

    @JsonProperty("updated_at")
    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime updatedAt;

}
