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
 * Persistence model for the audit_logs table; foreign keys retain their
 * explicit UUID identifiers.
 */
@Entity
@Table(name = "audit_logs")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class AuditLog {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("actor_user_id")
    @Column(name = "actor_user_id", nullable = true)
    private UUID actorUserId;

    @JsonProperty("action")
    @Column(name = "action", nullable = false, length = 100)
    private String action;

    @JsonProperty("entity_type")
    @Column(name = "entity_type", nullable = true, length = 100)
    private String entityType;

    @JsonProperty("entity_id")
    @Column(name = "entity_id", nullable = true)
    private UUID entityId;

    @JsonProperty("old_value")
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "old_value", nullable = true, columnDefinition = "jsonb")
    private Object oldValue;

    @JsonProperty("new_value")
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_value", nullable = true, columnDefinition = "jsonb")
    private Object newValue;

    @JsonProperty("ip_address")
    @ColumnTransformer(read = "ip_address::text", write = "?::inet")
    @Column(name = "ip_address", nullable = true, columnDefinition = "inet")
    private String ipAddress;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

}
