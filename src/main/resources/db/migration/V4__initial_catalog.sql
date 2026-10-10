-- Initial development catalog only. Prices and quotas are editable by administrators.
-- No privileged accounts, passwords, API secrets or invented embeddings are seeded.
INSERT INTO subscription_tiers (id, tier_code, display_name, price_monthly, currency) VALUES
    ('10000000-0000-0000-0000-000000000001', 'FREE', 'Free', 0, 'VND'),
    ('10000000-0000-0000-0000-000000000002', 'BASIC', 'Basic', 99000, 'VND'),
    ('10000000-0000-0000-0000-000000000003', 'PRO', 'Pro', 199000, 'VND');

INSERT INTO subscription_quotas (tier_id, feature_code, quota_value, quota_period) VALUES
    ('10000000-0000-0000-0000-000000000001', 'AI_TOTAL_TOKENS', 20000, 'MONTHLY'),
    ('10000000-0000-0000-0000-000000000001', 'CV_BUILD', 20000, 'MONTHLY'),
    ('10000000-0000-0000-0000-000000000001', 'CV_ANALYZE', 20000, 'MONTHLY'),
    ('10000000-0000-0000-0000-000000000001', 'JOB_MATCH_RERANK', 20000, 'MONTHLY'),
    ('10000000-0000-0000-0000-000000000001', 'JOB_ATS', 20000, 'MONTHLY'),
    ('10000000-0000-0000-0000-000000000001', 'ASSESSMENT_GENERATE', 20000, 'MONTHLY'),
    ('10000000-0000-0000-0000-000000000001', 'ROADMAP_GENERATE', 20000, 'MONTHLY'),
    ('10000000-0000-0000-0000-000000000001', 'APPLICATION_EMAIL', 20000, 'MONTHLY'),
    ('10000000-0000-0000-0000-000000000001', 'CAREER_CHAT', 20000, 'MONTHLY'),
    ('10000000-0000-0000-0000-000000000001', 'SALARY_ESTIMATE', 20000, 'MONTHLY'),
    ('10000000-0000-0000-0000-000000000002', 'AI_TOTAL_TOKENS', 200000, 'MONTHLY'),
    ('10000000-0000-0000-0000-000000000002', 'CV_BUILD', 200000, 'MONTHLY'),
    ('10000000-0000-0000-0000-000000000002', 'CV_ANALYZE', 200000, 'MONTHLY'),
    ('10000000-0000-0000-0000-000000000002', 'JOB_MATCH_RERANK', 200000, 'MONTHLY'),
    ('10000000-0000-0000-0000-000000000002', 'JOB_ATS', 200000, 'MONTHLY'),
    ('10000000-0000-0000-0000-000000000002', 'ASSESSMENT_GENERATE', 200000, 'MONTHLY'),
    ('10000000-0000-0000-0000-000000000002', 'ROADMAP_GENERATE', 200000, 'MONTHLY'),
    ('10000000-0000-0000-0000-000000000002', 'APPLICATION_EMAIL', 200000, 'MONTHLY'),
    ('10000000-0000-0000-0000-000000000002', 'CAREER_CHAT', 200000, 'MONTHLY'),
    ('10000000-0000-0000-0000-000000000002', 'SALARY_ESTIMATE', 200000, 'MONTHLY'),
    ('10000000-0000-0000-0000-000000000003', 'AI_TOTAL_TOKENS', 1000000, 'MONTHLY'),
    ('10000000-0000-0000-0000-000000000003', 'CV_BUILD', 1000000, 'MONTHLY'),
    ('10000000-0000-0000-0000-000000000003', 'CV_ANALYZE', 1000000, 'MONTHLY'),
    ('10000000-0000-0000-0000-000000000003', 'JOB_MATCH_RERANK', 1000000, 'MONTHLY'),
    ('10000000-0000-0000-0000-000000000003', 'JOB_ATS', 1000000, 'MONTHLY'),
    ('10000000-0000-0000-0000-000000000003', 'ASSESSMENT_GENERATE', 1000000, 'MONTHLY'),
    ('10000000-0000-0000-0000-000000000003', 'ROADMAP_GENERATE', 1000000, 'MONTHLY'),
    ('10000000-0000-0000-0000-000000000003', 'APPLICATION_EMAIL', 1000000, 'MONTHLY'),
    ('10000000-0000-0000-0000-000000000003', 'CAREER_CHAT', 1000000, 'MONTHLY'),
    ('10000000-0000-0000-0000-000000000003', 'SALARY_ESTIMATE', 1000000, 'MONTHLY');

INSERT INTO skills (id, name, slug, category, description) VALUES
    ('20000000-0000-0000-0000-000000000001', 'Java', 'java', 'Programming', 'Initial skill catalog entry for Java.'),
    ('20000000-0000-0000-0000-000000000002', 'Spring Boot', 'spring-boot', 'Backend', 'Initial skill catalog entry for Spring Boot.'),
    ('20000000-0000-0000-0000-000000000003', 'PostgreSQL', 'postgresql', 'Database', 'Initial skill catalog entry for PostgreSQL.'),
    ('20000000-0000-0000-0000-000000000004', 'SQL', 'sql', 'Database', 'Initial skill catalog entry for SQL.'),
    ('20000000-0000-0000-0000-000000000005', 'Python', 'python', 'Programming', 'Initial skill catalog entry for Python.'),
    ('20000000-0000-0000-0000-000000000006', 'JavaScript', 'javascript', 'Programming', 'Initial skill catalog entry for JavaScript.'),
    ('20000000-0000-0000-0000-000000000007', 'TypeScript', 'typescript', 'Programming', 'Initial skill catalog entry for TypeScript.'),
    ('20000000-0000-0000-0000-000000000008', 'React', 'react', 'Frontend', 'Initial skill catalog entry for React.'),
    ('20000000-0000-0000-0000-000000000009', 'Git', 'git', 'Tooling', 'Initial skill catalog entry for Git.'),
    ('20000000-0000-0000-0000-000000000010', 'Docker', 'docker', 'DevOps', 'Initial skill catalog entry for Docker.'),
    ('20000000-0000-0000-0000-000000000011', 'REST API', 'rest-api', 'Backend', 'Initial skill catalog entry for REST API.'),
    ('20000000-0000-0000-0000-000000000012', 'HTML', 'html', 'Frontend', 'Initial skill catalog entry for HTML.'),
    ('20000000-0000-0000-0000-000000000013', 'CSS', 'css', 'Frontend', 'Initial skill catalog entry for CSS.'),
    ('20000000-0000-0000-0000-000000000014', 'Data Structures', 'data-structures', 'Computer Science', 'Initial skill catalog entry for Data Structures.'),
    ('20000000-0000-0000-0000-000000000015', 'Machine Learning', 'machine-learning', 'AI', 'Initial skill catalog entry for Machine Learning.');

INSERT INTO skill_prerequisites (skill_id, prerequisite_id) VALUES
    ('20000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000001'),
    ('20000000-0000-0000-0000-000000000003', '20000000-0000-0000-0000-000000000004'),
    ('20000000-0000-0000-0000-000000000007', '20000000-0000-0000-0000-000000000006'),
    ('20000000-0000-0000-0000-000000000008', '20000000-0000-0000-0000-000000000006'),
    ('20000000-0000-0000-0000-000000000013', '20000000-0000-0000-0000-000000000012'),
    ('20000000-0000-0000-0000-000000000015', '20000000-0000-0000-0000-000000000005');

INSERT INTO resume_templates (id, name, career_path, language, is_trial_enabled) VALUES
    ('30000000-0000-0000-0000-000000000001', 'ATS-friendly Software Engineer', 'Software Engineer', 'EN', true),
    ('30000000-0000-0000-0000-000000000002', 'CV Chuyen vien Cong nghe', 'Software Engineer', 'VI', true);

INSERT INTO resumes (id, template_id, title, source, status, is_demo, language, raw_content, structured_data) VALUES
    ('40000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001',
     'Demo Software Engineer CV', 'MANUAL', 'READY', true, 'EN',
     'Demo Candidate. Junior Software Engineer. Java, Spring Boot, SQL, PostgreSQL, Git. Built a REST API course project. Bachelor of Computer Science. Demonstration data only.',
     '{"full_name":"Demo Candidate","summary":"Junior software engineer seeking a backend role. Demonstration data only.","skills":[{"name":"Java"},{"name":"Spring Boot"},{"name":"SQL"},{"name":"PostgreSQL"},{"name":"Git"}],"experience":[],"education":[{"school_name":"Demo University","degree":"Bachelor","major":"Computer Science"}],"projects":[{"project_name":"Course REST API","description":"Built a small API with Java, Spring Boot and PostgreSQL."}],"certifications":[]}'::jsonb);

INSERT INTO resume_skills (resume_id, skill_id, proficiency_level, source) VALUES
    ('40000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 'BEGINNER', 'DEMO'),
    ('40000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000002', 'BEGINNER', 'DEMO'),
    ('40000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000004', 'BEGINNER', 'DEMO'),
    ('40000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000003', 'BEGINNER', 'DEMO'),
    ('40000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000009', 'BEGINNER', 'DEMO');

INSERT INTO learning_resources (title, resource_type, provider, url, description, language, source_type, metadata_json) VALUES
    ('The Java Tutorials', 'DOCUMENTATION', 'Oracle', 'https://docs.oracle.com/javase/tutorial/', 'Official Java tutorials. Check the applicable Java version when studying.', 'en', 'ADMIN', '{"skill_slugs":["java"]}'::jsonb),
    ('Spring Boot Guides', 'DOCUMENTATION', 'Spring', 'https://spring.io/guides', 'Official practical guides for Spring applications.', 'en', 'ADMIN', '{"skill_slugs":["spring-boot","rest-api"]}'::jsonb),
    ('PostgreSQL Documentation', 'DOCUMENTATION', 'PostgreSQL', 'https://www.postgresql.org/docs/', 'Official PostgreSQL manual and SQL reference.', 'en', 'ADMIN', '{"skill_slugs":["postgresql","sql"]}'::jsonb),
    ('Python Tutorial', 'DOCUMENTATION', 'Python', 'https://docs.python.org/3/tutorial/', 'Official Python language tutorial.', 'en', 'ADMIN', '{"skill_slugs":["python"]}'::jsonb),
    ('React Learn', 'DOCUMENTATION', 'React', 'https://react.dev/learn', 'Official React learning materials.', 'en', 'ADMIN', '{"skill_slugs":["react","javascript"]}'::jsonb),
    ('Git Documentation', 'DOCUMENTATION', 'Git', 'https://git-scm.com/doc', 'Official Git documentation and book.', 'en', 'ADMIN', '{"skill_slugs":["git"]}'::jsonb),
    ('Docker Get Started', 'DOCUMENTATION', 'Docker', 'https://docs.docker.com/get-started/', 'Official Docker introductory documentation.', 'en', 'ADMIN', '{"skill_slugs":["docker"]}'::jsonb);

