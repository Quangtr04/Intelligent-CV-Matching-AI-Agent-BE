ALTER TABLE resume_analyses
    ADD COLUMN ats_report_json jsonb;

ALTER TABLE resume_analyses
    ADD CONSTRAINT resume_analyses_ats_report_object
    CHECK (ats_report_json IS NULL OR jsonb_typeof(ats_report_json) = 'object');

COMMENT ON COLUMN resume_analyses.ats_report_json IS
    'Deterministic ATS rubric (ats-v1): category scores, checks, keywords and rule feedback. NULL for historical analyses.';

COMMENT ON COLUMN job_resume_analyses.improvement_suggestions_json IS
    'Object with input_hash, items (suggestion strings) and ats_report (deterministic ATS rubric ats-v1).';
