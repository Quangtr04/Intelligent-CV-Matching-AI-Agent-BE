ALTER TABLE subscription_tiers
    ADD COLUMN tier_type varchar(20) NOT NULL DEFAULT 'SUBSCRIPTION',
    ADD COLUMN token_amount bigint NOT NULL DEFAULT 0;

ALTER TABLE subscription_tiers
    ADD CONSTRAINT ck_subscription_tier_type CHECK (tier_type IN ('SUBSCRIPTION', 'TOKEN_TOPUP')),
    ADD CONSTRAINT ck_subscription_tier_token_amount CHECK (token_amount >= 0),
    ADD CONSTRAINT ck_subscription_tier_product CHECK (
        (tier_type = 'SUBSCRIPTION' AND token_amount = 0)
        OR (tier_type = 'TOKEN_TOPUP' AND token_amount > 0)
    );

ALTER TABLE payment_orders
    DROP CONSTRAINT ck_payment_order_product;

ALTER TABLE payment_orders
    ADD CONSTRAINT ck_payment_order_product CHECK (
        (order_type = 'SUBSCRIPTION' AND tier_id IS NOT NULL AND duration_months IS NOT NULL
            AND consultation_order_id IS NULL)
        OR (order_type = 'CONSULTATION' AND tier_id IS NULL AND duration_months IS NULL
            AND consultation_order_id IS NOT NULL AND subscription_id IS NULL)
        OR (order_type = 'TOKEN_TOPUP' AND tier_id IS NOT NULL AND duration_months IS NULL
            AND consultation_order_id IS NULL AND subscription_id IS NULL)
    );

CREATE TABLE user_token_wallets (
    user_id uuid PRIMARY KEY REFERENCES users(id),
    balance bigint NOT NULL DEFAULT 0 CHECK (balance >= 0),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE token_wallet_ledger (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id),
    payment_order_id uuid UNIQUE REFERENCES payment_orders(id),
    entry_type varchar(20) NOT NULL CHECK (entry_type IN ('PURCHASE', 'SPEND', 'REFUND', 'ADJUSTMENT')),
    amount bigint NOT NULL CHECK (amount > 0),
    balance_after bigint NOT NULL CHECK (balance_after >= 0),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_token_wallet_ledger_user ON token_wallet_ledger(user_id, created_at DESC);

ALTER TABLE ai_token_reservations
    ADD COLUMN subscription_quota_remaining bigint NOT NULL DEFAULT 0,
    ADD COLUMN wallet_reserved_tokens bigint NOT NULL DEFAULT 0;
