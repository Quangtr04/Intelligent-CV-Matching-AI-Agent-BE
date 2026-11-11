-- Existing bookings intentionally remain unattached; never infer a candidate's latest CV.
ALTER TABLE consultation_bookings
    ADD COLUMN resume_id uuid REFERENCES resumes(id) ON DELETE SET NULL,
    ADD COLUMN job_id uuid REFERENCES market_job_posts(id) ON DELETE SET NULL;

ALTER TABLE consultation_threads
    ADD COLUMN resume_id uuid REFERENCES resumes(id) ON DELETE SET NULL,
    ADD COLUMN job_id uuid REFERENCES market_job_posts(id) ON DELETE SET NULL;

CREATE INDEX ix_consultation_bookings_resume_id ON consultation_bookings(resume_id);
CREATE INDEX ix_consultation_bookings_job_id ON consultation_bookings(job_id);
