ALTER TABLE resume_analyses
    ADD COLUMN writing_quality_json jsonb;

ALTER TABLE resume_analyses
    ADD CONSTRAINT resume_analyses_writing_quality_object
    CHECK (writing_quality_json IS NULL OR jsonb_typeof(writing_quality_json) = 'object');

COMMENT ON COLUMN resume_analyses.writing_quality_json IS
    'Versioned AI writing rubric, grounded evidence, content score and aggregate weights. NULL for historical or rule-based analyses.';
