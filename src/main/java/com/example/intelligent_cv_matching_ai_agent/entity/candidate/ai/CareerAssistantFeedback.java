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
 * Persistence model for the career_assistant_feedback table; foreign keys
 * retain their explicit UUID identifiers.
 */
@Entity
@Table(name = "career_assistant_feedback")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class CareerAssistantFeedback {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("message_id")
    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @JsonProperty("user_id")
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @JsonProperty("rating")
    @Column(name = "rating", nullable = true)
    private Short rating;

    @JsonProperty("is_helpful")
    @Column(name = "is_helpful", nullable = true)
    private Boolean isHelpful;

    @JsonProperty("feedback_text")
    @Column(name = "feedback_text", nullable = true, columnDefinition = "text")
    private String feedbackText;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

}
