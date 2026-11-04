-- Add MANAGEMENT role support, internal management profiles/permissions, task assignments, and job creator/reviewer tracking.

ALTER TABLE users
    DROP CONSTRAINT users_role_check,
    ADD CONSTRAINT users_role_check
        CHECK (role IN ('CANDIDATE', 'CAREER_CONSULTANT', 'ADMIN', 'MANAGEMENT'));

ALTER TABLE announcement_targets
    DROP CONSTRAINT announcement_targets_target_role_check,
    ADD CONSTRAINT announcement_targets_target_role_check
        CHECK (target_role IN ('CANDIDATE', 'CAREER_CONSULTANT', 'ADMIN', 'MANAGEMENT'));

CREATE TABLE management_profiles (
    user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    sub_role varchar(32) NOT NULL DEFAULT 'MANAGER'
        CHECK (sub_role IN ('MANAGER', 'STAFF')),
    permissions jsonb NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(permissions) = 'array'),
    manager_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    department varchar(120),
    notes text,
    updated_by_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_management_profiles_sub_role ON management_profiles(sub_role);
CREATE INDEX ix_management_profiles_manager ON management_profiles(manager_user_id);

CREATE TABLE management_tasks (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    title varchar(255) NOT NULL,
    description text,
    task_type varchar(32) NOT NULL
        CHECK (task_type IN ('JOB_ENTRY', 'JOB_SOURCING', 'JOB_REVIEW', 'GENERAL')),
    assigned_by_user_id uuid NOT NULL REFERENCES users(id),
    assigned_to_user_id uuid NOT NULL REFERENCES users(id),
    role_category varchar(100),
    source_site varchar(50),
    keyword varchar(255),
    target_location varchar(120),
    target_count int NOT NULL DEFAULT 1 CHECK (target_count >= 1 AND target_count <= 1000),
    priority varchar(20) NOT NULL DEFAULT 'NORMAL'
        CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    status varchar(32) NOT NULL DEFAULT 'TODO'
        CHECK (status IN ('TODO', 'IN_PROGRESS', 'DONE', 'CANCELLED')),
    due_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_management_tasks_assigned_to ON management_tasks(assigned_to_user_id, status, created_at DESC);
CREATE INDEX ix_management_tasks_assigned_by ON management_tasks(assigned_by_user_id, created_at DESC);

ALTER TABLE market_job_posts
    DROP CONSTRAINT market_job_posts_status_check,
    ADD CONSTRAINT market_job_posts_status_check
        CHECK (status IN ('PENDING', 'ACTIVE', 'REJECTED', 'EXPIRED', 'REMOVED', 'INVALID')),
    ADD COLUMN created_by_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN ingestion_method varchar(32) NOT NULL DEFAULT 'MANUAL'
        CHECK (ingestion_method IN ('MANUAL', 'CRAWL', 'SCHEDULED_CRAWL')),
    ADD COLUMN task_id uuid REFERENCES management_tasks(id) ON DELETE SET NULL,
    ADD COLUMN reviewed_by_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN reviewed_at timestamptz,
    ADD COLUMN review_note text;

CREATE INDEX ix_market_job_posts_created_by ON market_job_posts(created_by_user_id, collected_at DESC);
CREATE INDEX ix_market_job_posts_task_id ON market_job_posts(task_id);
CREATE INDEX ix_market_job_posts_status_collected ON market_job_posts(status, collected_at DESC);

ALTER TABLE market_crawl_runs
    ADD COLUMN task_id uuid REFERENCES management_tasks(id) ON DELETE SET NULL;

CREATE INDEX ix_market_crawl_runs_task_id ON market_crawl_runs(task_id);

