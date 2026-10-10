-- Operational addition to the logical DBML: atomically reserve a user's budget
-- before an external AI call, then complete or release the reservation.
CREATE TABLE ai_token_reservations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users (id),
    feature_code varchar(100) NOT NULL,
    reserved_tokens bigint NOT NULL CHECK (reserved_tokens >= 0),
    consumed_tokens bigint NOT NULL DEFAULT 0 CHECK (consumed_tokens >= 0 AND consumed_tokens <= reserved_tokens),
    status varchar(20) NOT NULL DEFAULT 'RESERVED' CHECK (status IN ('RESERVED', 'COMPLETED', 'CANCELLED')),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_ai_reservations_user_status_time ON ai_token_reservations (user_id, status, created_at);

-- Deduplicate signed provider notifications before mutating payment state.
CREATE TABLE payment_webhook_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    provider varchar(20) NOT NULL,
    event_id varchar(255) NOT NULL,
    payment_order_id uuid NOT NULL REFERENCES payment_orders (id),
    received_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (provider, event_id)
);
CREATE INDEX ix_payment_webhook_events_payment_order ON payment_webhook_events (payment_order_id);
