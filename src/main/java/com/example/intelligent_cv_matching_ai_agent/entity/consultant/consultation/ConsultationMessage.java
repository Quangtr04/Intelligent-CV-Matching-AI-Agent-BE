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
 * Persistence model for the consultation_messages table; foreign keys retain
 * their explicit UUID identifiers.
 */
@Entity
@Table(name = "consultation_messages")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class ConsultationMessage {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("thread_id")
    @Column(name = "thread_id", nullable = false)
    private UUID threadId;

    @JsonProperty("sender_user_id")
    @Column(name = "sender_user_id", nullable = false)
    private UUID senderUserId;

    @JsonProperty("message_type")
    @Column(name = "message_type", nullable = false, length = 30)
    private String messageType;

    @JsonProperty("content")
    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @JsonProperty("sent_at")
    @Column(name = "sent_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime sentAt;

    @JsonProperty("read_at")
    @Column(name = "read_at", nullable = true, columnDefinition = "timestamptz")
    private OffsetDateTime readAt;

}
