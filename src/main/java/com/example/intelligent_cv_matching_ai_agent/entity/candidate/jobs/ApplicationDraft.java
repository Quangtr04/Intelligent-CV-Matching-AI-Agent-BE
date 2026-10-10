package com.example.intelligent_cv_matching_ai_agent.entity.candidate.jobs;

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
 * Persistence model for the application_drafts table; foreign keys retain their
 * explicit UUID identifiers.
 */
@Entity
@Table(name = "application_drafts")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class ApplicationDraft {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("user_id")
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @JsonProperty("market_job_id")
    @Column(name = "market_job_id", nullable = false)
    private UUID marketJobId;

    @JsonProperty("resume_id")
    @Column(name = "resume_id", nullable = true)
    private UUID resumeId;

    @JsonProperty("draft_type")
    @Column(name = "draft_type", nullable = false, length = 30)
    private String draftType;

    @JsonProperty("content")
    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @JsonProperty("model_used")
    @Column(name = "model_used", nullable = true, length = 100)
    private String modelUsed;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

    @JsonProperty("updated_at")
    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime updatedAt;

}
