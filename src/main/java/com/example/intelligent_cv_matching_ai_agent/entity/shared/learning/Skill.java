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
 * Persistence model for the skills table; foreign keys retain their explicit
 * UUID identifiers.
 */
@Entity
@Table(name = "skills")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class Skill {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("name")
    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @JsonProperty("slug")
    @Column(name = "slug", nullable = false, length = 150)
    private String slug;

    @JsonProperty("category")
    @Column(name = "category", nullable = true, length = 50)
    private String category;

    @JsonProperty("major")
    @Column(name = "major", nullable = true, length = 50)
    private String major;

    @JsonProperty("description")
    @Column(name = "description", nullable = true, columnDefinition = "text")
    private String description;

    @JsonProperty("difficulty_level")
    @Column(name = "difficulty_level", nullable = true)
    private Short difficultyLevel;

    @JsonProperty("is_active")
    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

    @JsonProperty("updated_at")
    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime updatedAt;

}
