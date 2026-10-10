package com.example.intelligent_cv_matching_ai_agent.entity.admin.monitoring;

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
 * Persistence model for the system_logs table; foreign keys retain their
 * explicit UUID identifiers.
 */
@Entity
@Table(name = "system_logs")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class SystemLog {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("level")
    @Column(name = "level", nullable = true, length = 30)
    private String level;

    @JsonProperty("component")
    @Column(name = "component", nullable = true, length = 100)
    private String component;

    @JsonProperty("event_type")
    @Column(name = "event_type", nullable = true, length = 100)
    private String eventType;

    @JsonProperty("message")
    @Column(name = "message", nullable = true, columnDefinition = "text")
    private String message;

    @JsonProperty("context_json")
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context_json", nullable = true, columnDefinition = "jsonb")
    private Object contextJson;

    @JsonProperty("user_id")
    @Column(name = "user_id", nullable = true)
    private UUID userId;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

}
