-- Purchases grant session credits. Historical orders keep their original fulfillment path.
ALTER TABLE consultation_services ADD COLUMN sessions_count integer NOT NULL DEFAULT 1
    CHECK (sessions_count BETWEEN 1 AND 100);
ALTER TABLE consultation_services ADD COLUMN is_session_pack boolean NOT NULL DEFAULT false;
ALTER TABLE consultation_services ADD CONSTRAINT ck_consultation_session_pack
    CHECK (NOT is_session_pack OR (delivery_mode='LIVE' AND duration_minutes IN (30,60)));

UPDATE consultation_services SET is_session_pack=true,updated_at=now()
WHERE delivery_mode='LIVE' AND duration_minutes IN (30,60);
UPDATE consultation_services SET is_active=false,updated_at=now() WHERE delivery_mode='ASYNC';

INSERT INTO consultation_services
    (service_code,display_name,description,delivery_mode,duration_minutes,price,sessions_count,is_session_pack)
VALUES
    ('CAREER_PACK_5_30','Gói 5 lượt tư vấn 30 phút',
     'Năm buổi tư vấn, mỗi buổi 30 phút. Chọn cố vấn và lịch sau khi thanh toán.',
     'LIVE',30,495000,5,true),
    ('CAREER_PACK_5_60','Gói 5 lượt tư vấn 60 phút',
     'Năm buổi tư vấn, mỗi buổi 60 phút. Chọn cố vấn và lịch sau khi thanh toán.',
     'LIVE',60,895000,5,true);

ALTER TABLE consultation_orders ADD COLUMN sessions_total integer NOT NULL DEFAULT 1
    CHECK (sessions_total BETWEEN 1 AND 100);
ALTER TABLE consultation_orders ADD COLUMN sessions_remaining integer NOT NULL DEFAULT 0;
ALTER TABLE consultation_orders ADD COLUMN is_session_pack boolean NOT NULL DEFAULT false;
ALTER TABLE consultation_orders ADD CONSTRAINT ck_consultation_remaining_sessions
    CHECK (sessions_remaining BETWEEN 0 AND sessions_total);
ALTER TABLE consultation_orders ADD CONSTRAINT ck_consultation_order_pack
    CHECK (NOT is_session_pack OR (delivery_mode='LIVE' AND duration_minutes IN (30,60) AND thread_id IS NULL));

CREATE TABLE consultant_profiles (
    user_id uuid PRIMARY KEY REFERENCES users(id),
    bio text NOT NULL DEFAULT '',
    target_roles jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(target_roles)='array')
);

CREATE TABLE consultant_busy_slots (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id),
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    description text NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (ends_at>starts_at)
);
CREATE INDEX ix_consultant_busy_intervals ON consultant_busy_slots(user_id,starts_at,ends_at);

CREATE TABLE consultation_bookings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    candidate_user_id uuid NOT NULL REFERENCES users(id),
    consultant_user_id uuid NOT NULL REFERENCES users(id),
    credit_order_id uuid NOT NULL REFERENCES consultation_orders(id),
    target_role varchar(150) NOT NULL,
    subject varchar(255) NOT NULL,
    description text NOT NULL,
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    duration_minutes smallint NOT NULL CHECK (duration_minutes IN (30,60)),
    status varchar(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','ACCEPTED','DECLINED','CANCELLED','COMPLETED')),
    thread_id uuid UNIQUE REFERENCES consultation_threads(id),
    credit_refunded boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    accepted_at timestamptz,
    completed_at timestamptz,
    CHECK (candidate_user_id<>consultant_user_id),
    CHECK (ends_at=starts_at+duration_minutes*interval '1 minute'),
    CHECK (NOT credit_refunded OR status IN ('DECLINED','CANCELLED'))
);
CREATE INDEX ix_consultation_booking_candidate ON consultation_bookings(candidate_user_id,starts_at DESC);
CREATE INDEX ix_consultation_booking_consultant ON consultation_bookings(consultant_user_id,starts_at,ends_at);

CREATE TABLE consultation_ratings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id uuid NOT NULL UNIQUE REFERENCES consultation_bookings(id),
    candidate_user_id uuid NOT NULL REFERENCES users(id),
    consultant_user_id uuid NOT NULL REFERENCES users(id),
    rating smallint NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment text NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_consultation_rating_consultant ON consultation_ratings(consultant_user_id);
