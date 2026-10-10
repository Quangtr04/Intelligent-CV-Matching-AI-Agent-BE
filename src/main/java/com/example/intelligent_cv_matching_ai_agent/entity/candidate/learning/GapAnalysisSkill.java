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
 * Persistence model for the gap_analysis_skills table; foreign keys retain
 * their explicit UUID identifiers.
 */
@Entity
@Table(name = "gap_analysis_skills")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class GapAnalysisSkill {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("gap_analysis_id")
    @Column(name = "gap_analysis_id", nullable = false)
    private UUID gapAnalysisId;

    @JsonProperty("skill_id")
    @Column(name = "skill_id", nullable = true)
    private UUID skillId;

    @JsonProperty("skill_name")
    @Column(name = "skill_name", nullable = false, length = 150)
    private String skillName;

    @JsonProperty("gap_status")
    @Column(name = "gap_status", nullable = false, length = 20)
    private String gapStatus;

    @JsonProperty("current_level")
    @Column(name = "current_level", nullable = true, length = 20)
    private String currentLevel;

    @JsonProperty("target_level")
    @Column(name = "target_level", nullable = true, length = 20)
    private String targetLevel;

    @JsonProperty("urgency_score")
    @Column(name = "urgency_score", nullable = true)
    private Short urgencyScore;

    @JsonProperty("reasoning")
    @Column(name = "reasoning", nullable = true, columnDefinition = "text")
    private String reasoning;

    @JsonProperty("is_required_for_target")
    @Column(name = "is_required_for_target", nullable = false)
    private Boolean isRequiredForTarget;

}
