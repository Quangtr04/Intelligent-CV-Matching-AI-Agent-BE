-- Assigned occurrences preserve a fixed slot grid; recurrence is expanded atomically.
CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE TABLE consultant_work_shifts (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 consultant_user_id uuid NOT NULL REFERENCES users(id),
 assigned_by uuid NOT NULL REFERENCES users(id),
 starts_at timestamptz NOT NULL,
 ends_at timestamptz NOT NULL,
 lunch_starts_at timestamptz,
 lunch_ends_at timestamptz,
 series_id uuid NOT NULL,
 cancelled_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(),
 CHECK (ends_at > starts_at),
 CHECK ((lunch_starts_at IS NULL AND lunch_ends_at IS NULL) OR
 (lunch_starts_at IS NOT NULL AND lunch_ends_at IS NOT NULL AND
 lunch_starts_at >= starts_at AND lunch_ends_at <= ends_at AND lunch_ends_at > lunch_starts_at)),
 EXCLUDE USING gist (consultant_user_id WITH =, tstzrange(starts_at,ends_at,'[)') WITH &&)
 WHERE (cancelled_at IS NULL)
);
CREATE INDEX ix_work_shifts_consultant_start ON consultant_work_shifts(consultant_user_id,starts_at)
 WHERE cancelled_at IS NULL;
