package com.example.intelligent_cv_matching_ai_agent.entity.candidate.ai;

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
 * Persistence model for the career_assistant_conversations table; foreign keys
 * retain their explicit UUID identifiers.
 */
@Entity
@Table(name = "career_assistant_conversations")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class CareerAssistantConversation {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("user_id")
    @Column(name = "user_id", nullable = true)
    private UUID userId;

    @JsonProperty("guest_session_id")
    @Column(name = "guest_session_id", nullable = true)
    private UUID guestSessionId;

    @JsonProperty("title")
    @Column(name = "title", nullable = true, length = 255)
    private String title;

    @JsonProperty("status")
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @JsonProperty("context_type")
    @Column(name = "context_type", nullable = false, length = 30)
    private String contextType;

    @JsonProperty("related_job_id")
    @Column(name = "related_job_id", nullable = true)
    private UUID relatedJobId;

    @JsonProperty("related_resume_id")
    @Column(name = "related_resume_id", nullable = true)
    private UUID relatedResumeId;

    @JsonProperty("related_gap_analysis_id")
    @Column(name = "related_gap_analysis_id", nullable = true)
    private UUID relatedGapAnalysisId;

    @JsonProperty("related_roadmap_id")
    @Column(name = "related_roadmap_id", nullable = true)
    private UUID relatedRoadmapId;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

    @JsonProperty("updated_at")
    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime updatedAt;

    @JsonProperty("last_message_at")
    @Column(name = "last_message_at", nullable = true, columnDefinition = "timestamptz")
    private OffsetDateTime lastMessageAt;

}
