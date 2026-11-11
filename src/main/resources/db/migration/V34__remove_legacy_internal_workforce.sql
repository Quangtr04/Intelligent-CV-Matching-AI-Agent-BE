-- Retire the unused internal workforce module without deleting user accounts or job history.
-- Existing operational MANAGER accounts keep their role and sessions.
UPDATE refresh_tokens t
SET revoked_at = COALESCE(t.revoked_at, now())
FROM users u
WHERE t.user_id = u.id AND u.role = 'MANAGEMENT';

INSERT INTO candidate_profiles(user_id, created_at, updated_at)
SELECT id, now(), now() FROM users WHERE role = 'MANAGEMENT'
ON CONFLICT (user_id) DO NOTHING;

-- Never promote a retired internal account to a privileged role. Keep deleted users deleted.
UPDATE users
SET role = 'CANDIDATE',
    status = CASE WHEN status = 'DELETED' THEN 'DELETED' ELSE 'SUSPENDED' END,
    updated_at = now()
WHERE role = 'MANAGEMENT';

-- Removing retired role targets cannot broaden the audience: announcement reads require a matching target.
DELETE FROM announcement_targets WHERE target_role = 'MANAGEMENT';

ALTER TABLE users DROP CONSTRAINT users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check
    CHECK (role IN ('CANDIDATE', 'CAREER_CONSULTANT', 'MANAGER', 'ADMIN'));

ALTER TABLE announcement_targets DROP CONSTRAINT announcement_targets_target_role_check;
ALTER TABLE announcement_targets ADD CONSTRAINT announcement_targets_target_role_check
    CHECK (target_role IN ('CANDIDATE', 'CAREER_CONSULTANT', 'MANAGER', 'ADMIN'));

-- Keep generic job creator / reviewer attribution, while removing the obsolete work-assignment links.
ALTER TABLE market_job_posts DROP COLUMN task_id;
ALTER TABLE market_crawl_runs DROP COLUMN task_id;
DROP TABLE management_tasks;
DROP TABLE management_profiles;
