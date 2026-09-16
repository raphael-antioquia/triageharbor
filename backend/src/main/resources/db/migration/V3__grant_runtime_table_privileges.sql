-- Runtime allowlist for triageharbor_app, derived from current service SQL.
-- Future migrations that add runtime-used tables must grant explicitly.

GRANT SELECT
    ON TABLE projects
    TO triageharbor_app;

-- SELECT is also required by ON CONFLICT DO UPDATE ... RETURNING.
GRANT SELECT, INSERT, UPDATE
    ON TABLE incidents
    TO triageharbor_app;

GRANT INSERT
    ON TABLE events
    TO triageharbor_app;
