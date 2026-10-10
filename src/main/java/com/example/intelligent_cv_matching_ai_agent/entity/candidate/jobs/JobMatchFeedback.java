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
 * Persistence model for the job_match_feedback table; foreign keys retain their
 * explicit UUID identifiers.
 */
@Entity
@Table(name = "job_match_feedback")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class JobMatchFeedback {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("user_id")
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @JsonProperty("match_result_id")
    @Column(name = "match_result_id", nullable = false)
    private UUID matchResultId;

    @JsonProperty("feedback_type")
    @Column(name = "feedback_type", nullable = true, length = 20)
    private String feedbackType;

    @JsonProperty("rating")
    @Column(name = "rating", nullable = true)
    private Short rating;

    @JsonProperty("comment")
    @Column(name = "comment", nullable = true, columnDefinition = "text")
    private String comment;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

}
