package com.example.intelligent_cv_matching_ai_agent.entity.shared.resume;

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
 * Persistence model for the resume_templates table; foreign keys retain their
 * explicit UUID identifiers.
 */
@Entity
@Table(name = "resume_templates")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class ResumeTemplate {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("name")
    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @JsonProperty("career_path")
    @Column(name = "career_path", nullable = true, length = 150)
    private String careerPath;

    @JsonProperty("description")
    @Column(name = "description", nullable = false, length = 2000)
    private String description;

    @JsonProperty("html_content")
    @Column(name = "html_content", nullable = false, columnDefinition = "text")
    private String htmlContent;

    @JsonProperty("language")
    @Column(name = "language", nullable = true, length = 20)
    private String language;

    @JsonProperty("is_trial_enabled")
    @Column(name = "is_trial_enabled", nullable = false)
    private Boolean isTrialEnabled;

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
