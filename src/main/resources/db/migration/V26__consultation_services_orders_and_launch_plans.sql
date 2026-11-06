-- Launch catalog. Keep previous migrations immutable for existing installations.
UPDATE subscription_tiers SET display_name='Free', price_monthly=0, currency='VND', updated_at=now()
WHERE tier_code='FREE';
UPDATE subscription_tiers SET display_name='Basic', price_monthly=29000, currency='VND', updated_at=now()
WHERE tier_code='BASIC';
UPDATE subscription_tiers SET display_name='Pro', price_monthly=69000, currency='VND', updated_at=now()
WHERE tier_code='PRO';

-- All enabled AI features share the total budget; preserve disabled features.
UPDATE subscription_quotas q SET
    quota_value=CASE t.tier_code WHEN 'FREE' THEN 30000 WHEN 'BASIC' THEN 250000 ELSE 800000 END,
    quota_period=CASE t.tier_code WHEN 'FREE' THEN 'MONTHLY' ELSE 'SUBSCRIPTION' END
FROM subscription_tiers t WHERE q.tier_id=t.id AND t.tier_code IN ('FREE','BASIC','PRO')
AND (q.feature_code='AI_TOTAL_TOKENS' OR q.quota_value>0);

CREATE TABLE consultation_services (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    service_code varchar(50) NOT NULL UNIQUE,
    display_name varchar(150) NOT NULL,
    description text NOT NULL DEFAULT '',
    delivery_mode varchar(10) NOT NULL CHECK (delivery_mode IN ('ASYNC','LIVE')),
    duration_minutes smallint,
    price decimal(10,2) NOT NULL CHECK (price>0 AND price=trunc(price)),
    currency char(3) NOT NULL DEFAULT 'VND' CHECK (currency='VND'),
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((delivery_mode='ASYNC' AND duration_minutes IS NULL)
        OR (delivery_mode='LIVE' AND duration_minutes BETWEEN 1 AND 480))
);

INSERT INTO consultation_services(service_code,display_name,description,delivery_mode,duration_minutes,price) VALUES
('CV_REVIEW_ASYNC','Review CV bất đồng bộ','Một lần chuyên gia đánh giá và góp ý CV.','ASYNC',NULL,49000),
('CAREER_ROADMAP_ASYNC','Tư vấn Career Roadmap bất đồng bộ','Một yêu cầu tư vấn lộ trình nghề nghiệp.','ASYNC',NULL,79000),
('CAREER_CALL_30','Tư vấn trực tiếp 30 phút','Một buổi trao đổi trực tiếp với cố vấn.','LIVE',30,99000),
('CAREER_CALL_60','Tư vấn trực tiếp 60 phút','Một buổi trao đổi trực tiếp với cố vấn.','LIVE',60,179000);

CREATE TABLE consultation_orders (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    candidate_user_id uuid NOT NULL REFERENCES users(id),
    service_id uuid NOT NULL REFERENCES consultation_services(id),
    service_code varchar(50) NOT NULL,
    service_name varchar(150) NOT NULL,
    delivery_mode varchar(10) NOT NULL CHECK (delivery_mode IN ('ASYNC','LIVE')),
    duration_minutes smallint,
    amount decimal(10,2) NOT NULL CHECK (amount>0),
    currency char(3) NOT NULL DEFAULT 'VND',
    subject varchar(255) NOT NULL,
    description text NOT NULL,
    thread_id uuid UNIQUE REFERENCES consultation_threads(id),
    status varchar(30) NOT NULL DEFAULT 'PENDING_PAYMENT'
        CHECK (status IN ('PENDING_PAYMENT','PAID','COMPLETED','CANCELLED')),
    paid_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_consultation_orders_candidate ON consultation_orders(candidate_user_id,created_at DESC);

ALTER TABLE payment_orders ALTER COLUMN tier_id DROP NOT NULL;
ALTER TABLE payment_orders ALTER COLUMN duration_months DROP NOT NULL;
ALTER TABLE payment_orders ADD COLUMN order_type varchar(20) NOT NULL DEFAULT 'SUBSCRIPTION';
ALTER TABLE payment_orders ADD COLUMN consultation_order_id uuid UNIQUE REFERENCES consultation_orders(id);
ALTER TABLE payment_orders ADD CONSTRAINT ck_payment_order_product CHECK (
    (order_type='SUBSCRIPTION' AND tier_id IS NOT NULL AND duration_months IS NOT NULL
        AND consultation_order_id IS NULL)
    OR (order_type='CONSULTATION' AND tier_id IS NULL AND duration_months IS NULL
        AND consultation_order_id IS NOT NULL AND subscription_id IS NULL)
);
