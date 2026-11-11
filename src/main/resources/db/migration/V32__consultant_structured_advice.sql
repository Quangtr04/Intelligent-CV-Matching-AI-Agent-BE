-- Advice reports are separate from chat and remain available after a completed Meet appointment.
CREATE TABLE consultation_advice_reports (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    thread_id uuid NOT NULL UNIQUE REFERENCES consultation_threads(id) ON DELETE CASCADE,
    candidate_user_id uuid NOT NULL REFERENCES users(id),
    consultant_user_id uuid NOT NULL REFERENCES users(id),
    resume_id uuid REFERENCES resumes(id),
    market_job_id uuid REFERENCES market_job_posts(id),
    status varchar(20) NOT NULL CHECK (status IN ('DRAFT','PUBLISHED')),
    advice_json jsonb NOT NULL CHECK (jsonb_typeof(advice_json)='object'),
    version bigint NOT NULL DEFAULT 1 CHECK (version>0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    CHECK (candidate_user_id<>consultant_user_id),
    CHECK ((status='PUBLISHED' AND published_at IS NOT NULL) OR (status='DRAFT' AND published_at IS NULL))
);
CREATE INDEX ix_consultation_advice_candidate ON consultation_advice_reports(candidate_user_id,updated_at DESC)
    WHERE status='PUBLISHED';

-- Private notes belong to one assigned consultant and are never included in candidate report responses.
CREATE TABLE consultation_private_notes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    thread_id uuid NOT NULL REFERENCES consultation_threads(id) ON DELETE CASCADE,
    consultant_user_id uuid NOT NULL REFERENCES users(id),
    notes text NOT NULL DEFAULT '' CHECK (char_length(notes)<=10000),
    rubric_json jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(rubric_json)='object'),
    version bigint NOT NULL DEFAULT 1 CHECK (version>0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (thread_id,consultant_user_id)
);
