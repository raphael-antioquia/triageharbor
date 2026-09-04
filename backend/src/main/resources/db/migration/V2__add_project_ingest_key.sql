ALTER TABLE projects
    ADD COLUMN ingest_key UUID NOT NULL DEFAULT gen_random_uuid();

ALTER TABLE projects
    ADD CONSTRAINT projects_ingest_key_unique
        UNIQUE (ingest_key);

COMMENT ON COLUMN projects.ingest_key IS
    'Public project-scoped capability for event ingestion; grants no read or administrative access.';
