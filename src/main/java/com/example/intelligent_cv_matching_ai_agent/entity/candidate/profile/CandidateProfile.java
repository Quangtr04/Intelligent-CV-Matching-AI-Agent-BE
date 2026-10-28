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
 * Persistence model for the candidate_profiles table; foreign keys retain their
 * explicit UUID identifiers.
 */
@Entity
@Table(name = "candidate_profiles")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class CandidateProfile {

    @JsonProperty("user_id")
    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @JsonProperty("current_job_title")
    @Column(name = "current_job_title", nullable = true, length = 255)
    private String currentJobTitle;

    @JsonProperty("current_company")
    @Column(name = "current_company", nullable = true, length = 255)
    private String currentCompany;

    @JsonProperty("target_job_title")
    @Column(name = "target_job_title", nullable = true, length = 255)
    private String targetJobTitle;

    @JsonProperty("career_goal")
    @Column(name = "career_goal", nullable = true, columnDefinition = "text")
    private String careerGoal;

    @JsonProperty("years_experience")
    @Column(name = "years_experience", nullable = true, precision = 4, scale = 1)
    private BigDecimal yearsExperience;

    @JsonProperty("expected_salary")
    @Column(name = "expected_salary", nullable = true, precision = 14, scale = 2)
    private BigDecimal expectedSalary;

    @JsonProperty("salary_currency")
    @Column(name = "salary_currency", nullable = true, length = 10)
    private String salaryCurrency;

    @JsonProperty("preferred_location")
    @Column(name = "preferred_location", nullable = true, length = 255)
    private String preferredLocation;

    @JsonProperty("workplace_preference")
    @Column(name = "workplace_preference", nullable = true, length = 50)
    private String workplacePreference;

    @JsonProperty("employment_type")
    @Column(name = "employment_type", nullable = true, length = 50)
    private String employmentType;

    @JsonProperty("profile_summary")
    @Column(name = "profile_summary", nullable = true, columnDefinition = "text")
    private String profileSummary;

    @JsonProperty("preferences_json")
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "preferences_json", nullable = true, columnDefinition = "jsonb")
    private Object preferencesJson;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

    @JsonProperty("updated_at")
    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime updatedAt;

}
