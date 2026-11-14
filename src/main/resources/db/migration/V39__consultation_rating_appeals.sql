ALTER TABLE consultation_ratings ADD COLUMN is_removed boolean NOT NULL DEFAULT false;

CREATE TABLE consultation_rating_appeals (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    rating_id uuid NOT NULL UNIQUE REFERENCES consultation_ratings(id),
    consultant_user_id uuid NOT NULL REFERENCES users(id),
    reason text NOT NULL,
    evidence_url text NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','ACCEPTED','REJECTED')),
    reviewer_user_id uuid REFERENCES users(id),
    decision_reason text,
    created_at timestamptz NOT NULL DEFAULT now(),
    reviewed_at timestamptz
);
CREATE INDEX ix_rating_appeals_status ON consultation_rating_appeals(status,created_at);
