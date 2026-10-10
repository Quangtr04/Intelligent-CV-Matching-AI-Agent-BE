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
 * Persistence model for the resume_certifications table; foreign keys retain
 * their explicit UUID identifiers.
 */
@Entity
@Table(name = "resume_certifications")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class ResumeCertification {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("resume_id")
    @Column(name = "resume_id", nullable = false)
    private UUID resumeId;

    @JsonProperty("name")
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @JsonProperty("issuer")
    @Column(name = "issuer", nullable = true, length = 255)
    private String issuer;

    @JsonProperty("issue_date")
    @Column(name = "issue_date", nullable = true)
    private LocalDate issueDate;

    @JsonProperty("expiry_date")
    @Column(name = "expiry_date", nullable = true)
    private LocalDate expiryDate;

    @JsonProperty("credential_url")
    @Column(name = "credential_url", nullable = true, columnDefinition = "text")
    private String credentialUrl;

}
