package com.example.intelligent_cv_matching_ai_agent.entity.candidate.resume;

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
 * Persistence model for the resume_skills table; foreign keys retain their
 * explicit UUID identifiers.
 */
@Entity
@Table(name = "resume_skills")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
@IdClass(ResumeSkillId.class)
public class ResumeSkill {

    @JsonProperty("resume_id")
    @Id
    @Column(name = "resume_id", nullable = false)
    private UUID resumeId;

    @JsonProperty("skill_id")
    @Id
    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    @JsonProperty("proficiency_level")
    @Column(name = "proficiency_level", nullable = true, length = 50)
    private String proficiencyLevel;

    @JsonProperty("years_experience")
    @Column(name = "years_experience", nullable = true, precision = 4, scale = 1)
    private BigDecimal yearsExperience;

    @JsonProperty("source")
    @Column(name = "source", nullable = true, length = 50)
    private String source;

}
