CREATE TABLE projects (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE incidents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    project_id UUID NOT NULL,

    fingerprint VARCHAR(128) NOT NULL,
    fingerprint_version INTEGER NOT NULL,

    -- Snapshot derived from the event that initially creates the incident.
    -- Later matching events do not automatically replace it.
    title TEXT NOT NULL,

    status VARCHAR(32) NOT NULL DEFAULT 'open',

    occurrence_count BIGINT NOT NULL DEFAULT 1,

    -- Server-controlled receipt times rather than client-reported occurred_at.
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT incidents_project_fk
        FOREIGN KEY (project_id)
        REFERENCES projects(id)
        ON DELETE RESTRICT,

    CONSTRAINT incidents_project_id_id_unique
        UNIQUE (project_id, id),

    CONSTRAINT incidents_fingerprint_unique
        UNIQUE (project_id, fingerprint_version, fingerprint),

    CONSTRAINT incidents_fingerprint_version_positive
        CHECK (fingerprint_version > 0),

    CONSTRAINT incidents_occurrence_count_positive
        CHECK (occurrence_count > 0),

    CONSTRAINT incidents_seen_order_valid
        CHECK (last_seen_at >= first_seen_at),

    CONSTRAINT incidents_status_valid
        CHECK (status IN ('open', 'resolved'))
);

CREATE TABLE events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    project_id UUID NOT NULL,
    incident_id UUID NOT NULL,

    -- Client-reported time. This is untrusted and does not drive aggregation,
    -- retention, or future partition routing.
    occurred_at TIMESTAMPTZ NOT NULL,

    -- TriageHarbor-controlled receipt time.
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    exception_type TEXT,
    message TEXT NOT NULL,
    stack_trace TEXT,

    CONSTRAINT events_incident_project_fk
        FOREIGN KEY (project_id, incident_id)
        REFERENCES incidents(project_id, id)
        ON DELETE RESTRICT
);

CREATE INDEX incidents_project_last_seen_idx
    ON incidents (project_id, last_seen_at DESC);

COMMENT ON COLUMN incidents.title IS
    'Snapshot derived from the event that initially created the incident; not automatically maintained.';

COMMENT ON CONSTRAINT events_incident_project_fk ON events IS
    'V1 intentionally has no child-side index. Add an index beginning with (project_id, incident_id) before incident deletion or incident-event listing is introduced.';
