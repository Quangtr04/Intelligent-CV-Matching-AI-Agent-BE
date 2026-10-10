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
 * Persistence model for the assessment_questions table; foreign keys retain
 * their explicit UUID identifiers.
 */
@Entity
@Table(name = "assessment_questions")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class AssessmentQuestion {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("session_id")
    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @JsonProperty("sequence_order")
    @Column(name = "sequence_order", nullable = false)
    private Short sequenceOrder;

    @JsonProperty("part")
    @Column(name = "part", nullable = false)
    private Short part;

    @JsonProperty("question_text")
    @Column(name = "question_text", nullable = false, columnDefinition = "text")
    private String questionText;

    @JsonProperty("options")
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "options", nullable = false, columnDefinition = "jsonb")
    private Object options;

    @JsonProperty("correct_option")
    @Column(name = "correct_option", nullable = true, length = 1)
    private String correctOption;

    @JsonProperty("related_skill_id")
    @Column(name = "related_skill_id", nullable = true)
    private UUID relatedSkillId;

    @JsonProperty("related_skill_name")
    @Column(name = "related_skill_name", nullable = true, length = 150)
    private String relatedSkillName;

    @JsonProperty("explanation")
    @Column(name = "explanation", nullable = true, columnDefinition = "text")
    private String explanation;

}
