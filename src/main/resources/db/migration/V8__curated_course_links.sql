CREATE TABLE rag_course_links (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    url text NOT NULL UNIQUE,
    provider varchar(150) NOT NULL CHECK (provider IN ('YouTube', 'Coursera')),
    title varchar(255) NOT NULL,
    description text NOT NULL,
    skills jsonb NOT NULL CHECK (jsonb_typeof(skills) = 'array' AND jsonb_array_length(skills) BETWEEN 1 AND 30),
    language varchar(10) NOT NULL,
    is_free boolean NOT NULL,
    status varchar(30) NOT NULL DEFAULT 'PENDING_REVIEW'
        CHECK (status IN ('PENDING_REVIEW', 'APPROVED', 'ARCHIVED')),
    learning_resource_id uuid UNIQUE REFERENCES learning_resources(id),
    rag_document_id uuid UNIQUE REFERENCES rag_documents(id),
    created_by_user_id uuid NOT NULL REFERENCES users(id),
    reviewed_by_user_id uuid REFERENCES users(id),
    reviewed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_rag_course_approved_links CHECK (
        status <> 'APPROVED' OR (
            learning_resource_id IS NOT NULL AND rag_document_id IS NOT NULL
            AND reviewed_by_user_id IS NOT NULL AND reviewed_at IS NOT NULL
        )
    )
);

CREATE INDEX ix_rag_course_links_status ON rag_course_links(status, created_at DESC);

ALTER TABLE career_assistant_message_sources ADD COLUMN source_url text;
ALTER TABLE career_assistant_message_sources ADD COLUMN source_provider varchar(150);
