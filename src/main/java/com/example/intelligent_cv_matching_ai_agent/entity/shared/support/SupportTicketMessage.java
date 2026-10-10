package com.example.intelligent_cv_matching_ai_agent.entity.shared.support;

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
 * Persistence model for the support_ticket_messages table; foreign keys retain
 * their explicit UUID identifiers.
 */
@Entity
@Table(name = "support_ticket_messages")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class SupportTicketMessage {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("ticket_id")
    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    @JsonProperty("sender_user_id")
    @Column(name = "sender_user_id", nullable = false)
    private UUID senderUserId;

    @JsonProperty("content")
    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

}
