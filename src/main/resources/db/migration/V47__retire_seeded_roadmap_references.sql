-- Retire links seeded by the roadmap.sh catalog while preserving saved roadmap resources.
UPDATE learning_resources
SET is_active = false, updated_at = now()
WHERE is_active
  AND source_type = 'REFERENCE'
  AND provider = 'roadmap.sh'
  AND metadata_json->>'source_catalog_url' = 'https://roadmap.sh/roadmaps'
  AND metadata_json ? 'reference_slug';
