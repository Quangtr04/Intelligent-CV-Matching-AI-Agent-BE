-- Manager operates commercial/content workflows without account or AI administration.
ALTER TABLE users DROP CONSTRAINT users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check
    CHECK (role IN ('CANDIDATE', 'CAREER_CONSULTANT', 'ADMIN', 'MANAGEMENT', 'MANAGER'));

ALTER TABLE announcement_targets DROP CONSTRAINT announcement_targets_target_role_check;
ALTER TABLE announcement_targets ADD CONSTRAINT announcement_targets_target_role_check
    CHECK (target_role IN ('CANDIDATE', 'CAREER_CONSULTANT', 'ADMIN', 'MANAGEMENT', 'MANAGER'));
