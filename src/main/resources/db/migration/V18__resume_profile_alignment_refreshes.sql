-- A career-profile edit requests a fresh CV/profile comparison, independently of ATS.
-- One durable request per CV coalesces successive edits; history remains append-only.
CREATE TABLE resume_profile_alignment_refreshes (
    resume_id uuid PRIMARY KEY REFERENCES resumes(id),
    user_id uuid NOT NULL REFERENCES users(id),
    generation bigint NOT NULL DEFAULT 1 CHECK (generation > 0),
    requested_profile_hash varchar(64) NOT NULL CHECK (requested_profile_hash ~ '^[0-9a-f]{64}$'),
    status varchar(20) NOT NULL CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
    lease_token uuid,
    lease_until timestamptz,
    alignment_id uuid REFERENCES resume_profile_alignments(id),
    error_code varchar(40),
    message varchar(255),
    requested_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    completed_at timestamptz,
    CHECK ((status = 'RUNNING') = (lease_token IS NOT NULL AND lease_until IS NOT NULL))
);

CREATE INDEX ix_resume_profile_alignment_refreshes_pending
    ON resume_profile_alignment_refreshes(status,requested_at,resume_id)
    WHERE status IN ('QUEUED','RUNNING');
