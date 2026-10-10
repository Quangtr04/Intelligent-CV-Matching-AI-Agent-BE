package com.example.intelligent_cv_matching_ai_agent.entity.candidate.ai;

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
 * Persistence model for the career_assistant_messages table; foreign keys
 * retain their explicit UUID identifiers.
 */
@Entity
@Table(name = "career_assistant_messages")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class CareerAssistantMessage {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("conversation_id")
    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @JsonProperty("sender_type")
    @Column(name = "sender_type", nullable = false, length = 32)
    private String senderType;

    @JsonProperty("message_text")
    @Column(name = "message_text", nullable = false, columnDefinition = "text")
    private String messageText;

    @JsonProperty("message_format")
    @Column(name = "message_format", nullable = false, length = 20)
    private String messageFormat;

    @JsonProperty("input_tokens")
    @Column(name = "input_tokens", nullable = true)
    private Integer inputTokens;

    @JsonProperty("output_tokens")
    @Column(name = "output_tokens", nullable = true)
    private Integer outputTokens;

    @JsonProperty("model_used")
    @Column(name = "model_used", nullable = true, length = 80)
    private String modelUsed;

    @JsonProperty("retrieval_used")
    @Column(name = "retrieval_used", nullable = false)
    private Boolean retrievalUsed;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

}
