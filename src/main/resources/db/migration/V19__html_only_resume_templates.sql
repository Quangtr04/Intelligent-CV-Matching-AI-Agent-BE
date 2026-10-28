-- HTML is the sole stored layout representation. Retain template IDs and all CV references.
-- The old renderer options are allowlisted by V11; interpolate only those validated values.
DO $$
DECLARE
    template record;
    accent text;
    heading_color text;
    font text;
    document_html text;
    sections_html text;
    header_html text;
    is_vi boolean;
    is_plum boolean;
BEGIN
    FOR template IN SELECT id,language,layout_config FROM resume_templates WHERE html_content IS NULL LOOP
        accent := upper(template.layout_config->>'accentColor');
        heading_color := CASE WHEN (
            get_byte(decode(substring(accent FROM 2),'hex'),0)*299
            + get_byte(decode(substring(accent FROM 2),'hex'),1)*587
            + get_byte(decode(substring(accent FROM 2),'hex'),2)*114
        ) / 1000.0 > 200 THEN '#334155' ELSE accent END;
        font := CASE template.layout_config->>'fontFamily'
            WHEN 'Times New Roman' THEN '"Times New Roman",Times,serif'
            WHEN 'Calibri' THEN 'Calibri,Arial,sans-serif'
            ELSE 'Arial,sans-serif' END;
        is_vi := lower(coalesce(template.language,'en')) = 'vi';
        is_plum := template.layout_config->>'layout' = 'TOPCV_PLUM';
        SELECT string_agg(
            '<section data-cv-section="' || section || '"><h2>' ||
            CASE section
                WHEN 'summary' THEN CASE WHEN is_vi THEN 'Giới thiệu' ELSE 'Profile' END
                WHEN 'experience' THEN CASE WHEN is_vi THEN 'Kinh nghiệm' ELSE 'Experience' END
                WHEN 'education' THEN CASE WHEN is_vi THEN 'Học vấn' ELSE 'Education' END
                WHEN 'projects' THEN CASE WHEN is_vi THEN 'Dự án' ELSE 'Projects' END
                WHEN 'skills' THEN CASE WHEN is_vi THEN 'Kỹ năng' ELSE 'Skills' END
                WHEN 'certifications' THEN CASE WHEN is_vi THEN 'Chứng chỉ' ELSE 'Certifications' END
            END || '</h2></section>', '' ORDER BY ordinal
        ) INTO sections_html
        FROM jsonb_array_elements_text(template.layout_config->'sectionOrder') WITH ORDINALITY AS item(section,ordinal);
        header_html := '<h1 data-cv-field="fullName">' ||
            CASE WHEN is_vi THEN 'Họ và tên' ELSE 'Your Name' END ||
            '</h1><p class="cv-headline" data-cv-field="headline"></p>' ||
            '<p class="cv-contact" data-cv-field="contact"></p>';
        document_html := '<!DOCTYPE html><html lang="' || CASE WHEN is_vi THEN 'vi' ELSE 'en' END ||
            '"><head><meta charset="UTF-8"><title>CV</title><style>' ||
            '@page{size:A4;margin:0}*{box-sizing:border-box}body{margin:0;background:#fff;color:#334155;' ||
            'font-family:' || font || ';font-size:' || (template.layout_config->>'fontSize') ||
            'pt;line-height:1.55}article{min-height:38rem;background:#fff;overflow-wrap:anywhere}' ||
            'h1{margin:0;color:#0F172A;font-size:1.5em;font-weight:700;letter-spacing:.025em}' ||
            '.cv-headline{margin:4px 0 0;color:' || heading_color || ';font-size:.9em;font-weight:600}' ||
            '.cv-contact{margin:8px 0 0;color:#64748B;font-size:.7em;white-space:pre-line}' ||
            'section{break-inside:avoid;margin-bottom:20px}section:last-child{margin-bottom:0}' ||
            'h2{margin:0 0 8px;padding-bottom:4px;border-bottom:1px solid ' || accent ||
            '55;color:' || heading_color || ';font-size:.82em;text-transform:uppercase;letter-spacing:.05em}' ||
            '.cv-entry{margin-bottom:12px}.cv-entry p{margin:0}.cv-entry-title{font-size:.88em;font-weight:700;' ||
            'color:#0F172A}.cv-entry-subtitle{font-size:.8em;color:#475569}' ||
            '.cv-entry-period{font-size:.72em;color:#64748B}.cv-entry-text{font-size:.8em;white-space:pre-wrap}' ||
            '.cv-entry ul{display:flex;flex-wrap:wrap;gap:6px;list-style:none;padding:0;font-size:.75em}' ||
            '.cv-entry li{padding:2px 8px;border:1px solid #E2E8F0;border-radius:4px;background:#F8FAFC}' ||
            CASE WHEN is_plum THEN
                '.cv-page{display:grid;grid-template-columns:30% 70%}.cv-side{padding:28px 16px;background:' ||
                accent || '12;border-top:8px solid ' || accent || '}.cv-side h1{font-size:1.2em;line-height:1.25}' ||
                '.cv-side .cv-headline{margin-top:12px;font-size:.8em}.cv-side .cv-contact{margin-top:24px;' ||
                'font-size:.68em}.cv-main{min-width:0;padding:28px 20px}'
            ELSE
                '.cv-page{padding:28px}.cv-header{margin-bottom:20px;padding-bottom:16px;border-bottom:2px solid ' ||
                accent || '}'
            END ||
            '</style></head><body><article class="cv-page">' ||
            CASE WHEN is_plum THEN '<aside class="cv-side">' || header_html || '</aside><main class="cv-main">'
                ELSE '<header class="cv-header">' || header_html || '</header><main class="cv-main">' END ||
            sections_html || '</main></article></body></html>';
        UPDATE resume_templates SET html_content=document_html WHERE id=template.id;
    END LOOP;
END $$;

ALTER TABLE resume_templates ALTER COLUMN html_content SET NOT NULL;
ALTER TABLE resume_templates DROP CONSTRAINT resume_templates_html_content_check;
ALTER TABLE resume_templates ADD CONSTRAINT resume_templates_html_content_check CHECK (
    char_length(html_content) BETWEEN 1 AND 200000 AND html_content ~ '[^[:space:]]'
);
ALTER TABLE resume_templates DROP CONSTRAINT resume_templates_safe_layout_check;
ALTER TABLE resume_templates DROP COLUMN layout_config;
