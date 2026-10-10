package com.example.intelligent_cv_matching_ai_agent.entity.shared.jobs;

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
 * Persistence model for the market_job_skills table; foreign keys retain their
 * explicit UUID identifiers.
 */
@Entity
@Table(name = "market_job_skills")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class MarketJobSkill {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("market_job_id")
    @Column(name = "market_job_id", nullable = false)
    private UUID marketJobId;

    @JsonProperty("skill_id")
    @Column(name = "skill_id", nullable = true)
    private UUID skillId;

    @JsonProperty("skill_name")
    @Column(name = "skill_name", nullable = false, length = 100)
    private String skillName;

    @JsonProperty("normalized_skill")
    @Column(name = "normalized_skill", nullable = false, length = 100)
    private String normalizedSkill;

    @JsonProperty("is_required")
    @Column(name = "is_required", nullable = false)
    private Boolean isRequired;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

}
