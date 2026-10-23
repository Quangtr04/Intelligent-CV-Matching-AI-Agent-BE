-- Layouts contain only renderer options, never HTML, CSS or executable markup.
ALTER TABLE resume_templates
    ADD COLUMN description varchar(2000) NOT NULL DEFAULT '',
    ADD COLUMN layout_config jsonb NOT NULL DEFAULT '{"layout":"STANDARD_ATS","accentColor":"#1E3A8A","fontFamily":"Arial","fontSize":11,"sectionOrder":["summary","experience","education","projects","skills","certifications"]}'::jsonb;

-- Preserve unknown legacy languages; only normalize the supported old seed values.
UPDATE resume_templates SET language=lower(language) WHERE language IN ('EN','VI');
ALTER TABLE resume_templates ALTER COLUMN language SET DEFAULT 'vi';

UPDATE resume_templates
SET description='A simple ATS-friendly layout with a single-column reading order.'
WHERE id='30000000-0000-0000-0000-000000000001';
UPDATE resume_templates
SET description='A plum-accented CV layout for a clear professional profile.',
    layout_config=jsonb_set(
        jsonb_set(layout_config, '{layout}', '"TOPCV_PLUM"'::jsonb),
        '{accentColor}', '"#703D57"'::jsonb
    )
WHERE id='30000000-0000-0000-0000-000000000002';

ALTER TABLE resume_templates ADD CONSTRAINT resume_templates_safe_layout_check CHECK (
    COALESCE(
        jsonb_typeof(layout_config)='object'
        AND layout_config ?& ARRAY['layout','accentColor','fontFamily','fontSize','sectionOrder']
        AND layout_config - ARRAY['layout','accentColor','fontFamily','fontSize','sectionOrder']='{}'::jsonb
        AND layout_config->>'layout' IN ('STANDARD_ATS','TOPCV_PLUM')
        AND layout_config->>'accentColor' ~ '^#[0-9A-Fa-f]{6}$'
        AND layout_config->>'fontFamily' IN ('Arial','Calibri','Times New Roman')
        AND layout_config->'fontSize' IN ('10'::jsonb,'11'::jsonb,'12'::jsonb,'13'::jsonb,
            '14'::jsonb,'15'::jsonb,'16'::jsonb)
        AND CASE WHEN jsonb_typeof(layout_config->'sectionOrder')='array' THEN
            jsonb_array_length(layout_config->'sectionOrder')=6
            AND layout_config->'sectionOrder' @>
                '["summary","experience","education","projects","skills","certifications"]'::jsonb
        ELSE false END,
        false
    )
);
