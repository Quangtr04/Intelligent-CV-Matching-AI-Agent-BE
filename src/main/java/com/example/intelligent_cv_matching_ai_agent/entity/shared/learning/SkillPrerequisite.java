package com.example.intelligent_cv_matching_ai_agent.entity.shared.learning;

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
 * Persistence model for the skill_prerequisites table; foreign keys retain
 * their explicit UUID identifiers.
 */
@Entity
@Table(name = "skill_prerequisites")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
@IdClass(SkillPrerequisiteId.class)
public class SkillPrerequisite {

    @JsonProperty("skill_id")
    @Id
    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    @JsonProperty("prerequisite_id")
    @Id
    @Column(name = "prerequisite_id", nullable = false)
    private UUID prerequisiteId;

}
