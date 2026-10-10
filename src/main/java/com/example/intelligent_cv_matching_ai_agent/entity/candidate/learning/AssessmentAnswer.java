package com.example.intelligent_cv_matching_ai_agent.entity.candidate.learning;

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
 * Persistence model for the assessment_answers table; foreign keys retain their
 * explicit UUID identifiers.
 */
@Entity
@Table(name = "assessment_answers")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class AssessmentAnswer {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("session_id")
    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @JsonProperty("question_id")
    @Column(name = "question_id", nullable = false)
    private UUID questionId;

    @JsonProperty("selected_option")
    @Column(name = "selected_option", nullable = true, length = 1)
    private String selectedOption;

    @JsonProperty("is_correct")
    @Column(name = "is_correct", nullable = true)
    private Boolean isCorrect;

    @JsonProperty("answered_at")
    @Column(name = "answered_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime answeredAt;

}
