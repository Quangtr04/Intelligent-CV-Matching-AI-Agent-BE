package com.example.intelligent_cv_matching_ai_agent.entity.consultant.consultation;

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
 * Persistence model for the consultation_threads table; foreign keys retain
 * their explicit UUID identifiers.
 */
@Entity
@Table(name = "consultation_threads")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class ConsultationThread {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("candidate_user_id")
    @Column(name = "candidate_user_id", nullable = false)
    private UUID candidateUserId;

    @JsonProperty("consultant_user_id")
    @Column(name = "consultant_user_id", nullable = true)
    private UUID consultantUserId;

    @JsonProperty("status")
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

    @JsonProperty("closed_at")
    @Column(name = "closed_at", nullable = true, columnDefinition = "timestamptz")
    private OffsetDateTime closedAt;

    @JsonProperty("subject")
    @Column(name = "subject", nullable = true, length = 255)
    private String subject;

    @JsonProperty("request_type")
    @Column(name = "request_type", nullable = true, length = 50)
    private String requestType;

    @JsonProperty("request_description")
    @Column(name = "request_description", nullable = true, columnDefinition = "text")
    private String requestDescription;

    @JsonProperty("priority")
    @Column(name = "priority", nullable = true, length = 20)
    private String priority;

}
