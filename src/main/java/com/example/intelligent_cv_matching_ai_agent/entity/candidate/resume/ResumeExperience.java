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
 * Persistence model for the resume_experiences table; foreign keys retain their
 * explicit UUID identifiers.
 */
@Entity
@Table(name = "resume_experiences")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class ResumeExperience {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("resume_id")
    @Column(name = "resume_id", nullable = false)
    private UUID resumeId;

    @JsonProperty("company_name")
    @Column(name = "company_name", nullable = true, length = 255)
    private String companyName;

    @JsonProperty("job_title")
    @Column(name = "job_title", nullable = true, length = 255)
    private String jobTitle;

    @JsonProperty("start_date")
    @Column(name = "start_date", nullable = true)
    private LocalDate startDate;

    @JsonProperty("end_date")
    @Column(name = "end_date", nullable = true)
    private LocalDate endDate;

    @JsonProperty("is_current")
    @Column(name = "is_current", nullable = false)
    private Boolean isCurrent;

    @JsonProperty("description")
    @Column(name = "description", nullable = true, columnDefinition = "text")
    private String description;

}
