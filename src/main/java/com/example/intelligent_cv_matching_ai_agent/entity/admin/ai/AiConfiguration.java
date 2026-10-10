package com.example.intelligent_cv_matching_ai_agent.entity.admin.ai;

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
 * Persistence model for the ai_configurations table; foreign keys retain their
 * explicit UUID identifiers.
 */
@Entity
@Table(name = "ai_configurations")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class AiConfiguration {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("config_type")
    @Column(name = "config_type", nullable = false, length = 50)
    private String configType;

    @JsonProperty("provider")
    @Column(name = "provider", nullable = true, length = 100)
    private String provider;

    @JsonProperty("model_name")
    @Column(name = "model_name", nullable = true, length = 150)
    private String modelName;

    @JsonProperty("config_json")
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_json", nullable = true, columnDefinition = "jsonb")
    private Object configJson;

    @JsonProperty("version")
    @Column(name = "version", nullable = false)
    private Integer version;

    @JsonProperty("is_active")
    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    @JsonProperty("updated_by_user_id")
    @Column(name = "updated_by_user_id", nullable = true)
    private UUID updatedByUserId;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

    @JsonProperty("updated_at")
    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime updatedAt;

}
