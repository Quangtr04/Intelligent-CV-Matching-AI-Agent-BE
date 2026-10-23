-- Existing templates keep their renderer configuration. Optional HTML is validated at
-- the administrator API boundary and rendered only inside a sandboxed preview.
ALTER TABLE resume_templates ADD COLUMN html_content text;

ALTER TABLE resume_templates ADD CONSTRAINT resume_templates_html_content_check CHECK (
    html_content IS NULL OR (
        char_length(html_content) BETWEEN 1 AND 200000
        AND html_content ~ '[^[:space:]]'
    )
);
