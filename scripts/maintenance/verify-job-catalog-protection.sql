SELECT 'users' AS entity,count(*) AS row_count,md5(coalesce(string_agg(to_jsonb(t)::text,E'\n' ORDER BY to_jsonb(t)::text),'')) AS fingerprint FROM users t
UNION ALL SELECT 'resumes',count(*),md5(coalesce(string_agg(to_jsonb(t)::text,E'\n' ORDER BY to_jsonb(t)::text),'')) FROM resumes t
UNION ALL SELECT 'resume_skills',count(*),md5(coalesce(string_agg(to_jsonb(t)::text,E'\n' ORDER BY to_jsonb(t)::text),'')) FROM resume_skills t
UNION ALL SELECT 'candidate_profiles',count(*),md5(coalesce(string_agg(to_jsonb(t)::text,E'\n' ORDER BY to_jsonb(t)::text),'')) FROM candidate_profiles t
UNION ALL SELECT 'candidate_profile_skills',count(*),md5(coalesce(string_agg(to_jsonb(t)::text,E'\n' ORDER BY to_jsonb(t)::text),'')) FROM candidate_profile_skills t
UNION ALL SELECT 'skills',count(*),md5(coalesce(string_agg(to_jsonb(t)::text,E'\n' ORDER BY to_jsonb(t)::text),'')) FROM skills t
UNION ALL SELECT 'manual_jobs',count(*),md5(coalesce(string_agg(to_jsonb(t)::text,E'\n' ORDER BY t.id),'')) FROM market_job_posts t WHERE ingestion_method='MANUAL'
UNION ALL SELECT 'manual_job_skills',count(*),md5(coalesce(string_agg(to_jsonb(s)::text,E'\n' ORDER BY s.id),'')) FROM market_job_skills s JOIN market_job_posts j ON j.id=s.market_job_id WHERE j.ingestion_method='MANUAL'
UNION ALL SELECT 'job_source_fields',count(*),md5(coalesce(string_agg((to_jsonb(t)-'requirement_extraction')::text,E'\n' ORDER BY t.id),'')) FROM market_job_posts t
UNION ALL SELECT 'embeddings',count(*),md5(coalesce(string_agg(to_jsonb(t)::text,E'\n' ORDER BY t.id),'')) FROM market_job_embeddings t
UNION ALL SELECT 'public_base_tables',count(*),md5(coalesce(string_agg(table_name,E'\n' ORDER BY table_name),'')) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'
ORDER BY entity;
