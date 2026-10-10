package com.example.intelligent_cv_matching_ai_agent.entity.candidate.profile;

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
 * Persistence model for the candidate_profile_skills table; foreign keys retain
 * their explicit UUID identifiers.
 */
@Entity
@Table(name = "candidate_profile_skills")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
@IdClass(CandidateProfileSkillId.class)
public class CandidateProfileSkill {

    @JsonProperty("user_id")
    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

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

}
