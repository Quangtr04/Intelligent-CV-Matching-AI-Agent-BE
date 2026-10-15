-- Runtime overrides are created only when an administrator saves settings.
CREATE TABLE job_crawl_settings (
    id integer PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    enabled boolean NOT NULL,
    fixed_delay_millis bigint NOT NULL CHECK (fixed_delay_millis BETWEEN 60000 AND 2592000000),
    sources_json jsonb NOT NULL CHECK (jsonb_typeof(sources_json) = 'array'),
    actor_id uuid REFERENCES users(id),
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    updated_by uuid NOT NULL REFERENCES users(id),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (jsonb_array_length(sources_json) <= 20),
    CHECK (NOT enabled OR (actor_id IS NOT NULL AND jsonb_array_length(sources_json) >= 1))
);
