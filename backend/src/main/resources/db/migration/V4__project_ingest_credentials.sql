-- Fresh-data transition: intentionally retires legacy projects.ingest_key.
-- Existing V1-V3 files stay unchanged.
SET LOCAL lock_timeout = '5s';

CREATE TABLE project_ingest_credentials (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL,
    key_hash BYTEA NOT NULL,
    key_prefix VARCHAR(11) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ,

    CONSTRAINT project_ingest_credentials_project_fk
        FOREIGN KEY (project_id)
        REFERENCES projects(id)
        ON DELETE RESTRICT,

    CONSTRAINT project_ingest_credentials_hash_unique
        UNIQUE (key_hash),

    CONSTRAINT project_ingest_credentials_hash_length
        CHECK (octet_length(key_hash) = 32),

    CONSTRAINT project_ingest_credentials_prefix_format
        CHECK (key_prefix ~ '^th_[A-Za-z0-9_-]{8}$')
);

COMMENT ON CONSTRAINT project_ingest_credentials_project_fk
    ON project_ingest_credentials IS
    'No child-side project_id index yet: credential inventory is small, lifecycle operations are operator-only, and project deletion is not a current workflow.';

GRANT SELECT ON TABLE project_ingest_credentials
    TO triageharbor_app;

-- PostgreSQL drops the ingest-key UNIQUE constraint with its column.
ALTER TABLE projects DROP COLUMN ingest_key;
