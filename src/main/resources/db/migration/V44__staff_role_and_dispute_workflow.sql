-- Add operational STAFF role, CV template subscription package availability, and two-stage rating dispute workflow.

ALTER TABLE users DROP CONSTRAINT users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check
    CHECK (role IN ('CANDIDATE', 'CAREER_CONSULTANT', 'STAFF', 'MANAGER', 'ADMIN'));

ALTER TABLE announcement_targets DROP CONSTRAINT announcement_targets_target_role_check;
ALTER TABLE announcement_targets ADD CONSTRAINT announcement_targets_target_role_check
    CHECK (target_role IN ('CANDIDATE', 'CAREER_CONSULTANT', 'STAFF', 'MANAGER', 'ADMIN'));

ALTER TABLE resume_templates
    ADD COLUMN allowed_tier_codes jsonb NOT NULL DEFAULT '["FREE","BASIC","PRO"]'::jsonb
        CHECK (jsonb_typeof(allowed_tier_codes) = 'array');

ALTER TABLE consultation_rating_appeals
    DROP CONSTRAINT consultation_rating_appeals_status_check;

ALTER TABLE consultation_rating_appeals
    ADD CONSTRAINT consultation_rating_appeals_status_check
        CHECK (status IN ('PENDING', 'STAFF_APPROVED', 'STAFF_REJECTED', 'ACCEPTED', 'REJECTED')),
    ADD COLUMN staff_reviewer_user_id uuid REFERENCES users(id),
    ADD COLUMN staff_decision varchar(20)
        CHECK (staff_decision IS NULL OR staff_decision IN ('APPROVED', 'REJECTED')),
    ADD COLUMN staff_decision_reason text,
    ADD COLUMN staff_reviewed_at timestamptz,
    ADD COLUMN forwarded_to_manager_at timestamptz;

CREATE TABLE consultation_rating_appeal_history (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    appeal_id uuid NOT NULL REFERENCES consultation_rating_appeals(id) ON DELETE CASCADE,
    actor_user_id uuid NOT NULL REFERENCES users(id),
    actor_role varchar(32) NOT NULL,
    stage varchar(32) NOT NULL CHECK (stage IN ('SUBMISSION', 'PRELIMINARY_REVIEW', 'FINAL_REVIEW')),
    from_status varchar(20),
    to_status varchar(20) NOT NULL,
    decision varchar(20) NOT NULL,
    reason text,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_rating_appeal_history_appeal
    ON consultation_rating_appeal_history(appeal_id, created_at, id);
