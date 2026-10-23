-- Preserve existing analyses; new content assessments use the nullable rubric snapshot.
-- Some existing installations already received this mapped column outside Flyway.
DO $$
DECLARE
    existing_type regtype;
    existing_not_null boolean;
BEGIN
    SELECT atttypid::regtype, attnotnull
    INTO existing_type, existing_not_null
    FROM pg_attribute
    WHERE attrelid = 'resume_analyses'::regclass
      AND attname = 'content_quality_json'
      AND NOT attisdropped;

    IF NOT FOUND THEN
        ALTER TABLE resume_analyses ADD COLUMN content_quality_json jsonb;
    ELSIF existing_type <> 'jsonb'::regtype OR existing_not_null THEN
        RAISE EXCEPTION USING
            ERRCODE = '42804',
            MESSAGE = format(
                'resume_analyses.content_quality_json must be nullable JSONB; found type %s, NOT NULL %s',
                existing_type,
                existing_not_null
            );
    END IF;
END
$$;
