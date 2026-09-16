-- One-shot, convergent provisioning of TriageHarbor's restricted database roles.
-- Run as triageharbor_admin over TCP. Creates no database and no tables:
-- the image creates the database; Flyway owns the schema.
-- Passwords come from the environment. Never run with psql -a/-e/--echo-*.
-- Password statements are suppressed from PostgreSQL core statement logging;
-- third-party logging extensions are out of scope.

\set ON_ERROR_STOP on
\set QUIET on

\getenv migrator_password DB_MIGRATOR_PASSWORD
\getenv app_password DB_APP_PASSWORD

\if :{?migrator_password}
\else
DO $$ BEGIN RAISE EXCEPTION 'DB_MIGRATOR_PASSWORD is not set'; END $$;
\endif

\if :{?app_password}
\else
DO $$ BEGIN RAISE EXCEPTION 'DB_APP_PASSWORD is not set'; END $$;
\endif

-- Transaction sampling is decided when a transaction starts, so this must be
-- set before BEGIN (session scope; the connection ends with the script).
SET log_transaction_sample_rate = 0;

BEGIN;

-- Core PostgreSQL logging paths that could record password literals.
SET LOCAL log_statement = 'none';
SET LOCAL log_min_duration_statement = -1;
SET LOCAL log_min_duration_sample = -1;
SET LOCAL log_min_error_statement = 'panic';
SET LOCAL debug_print_parse = off;
SET LOCAL debug_print_rewritten = off;
SET LOCAL debug_print_plan = off;
SET LOCAL password_encryption = 'scram-sha-256';

-- Refuse maintenance databases: the revokes below would lock the app out.
SELECT current_database() NOT IN ('postgres', 'template0', 'template1')
    AS database_ok
\gset

\if :database_ok
\else
DO $$ BEGIN RAISE EXCEPTION 'refusing to bootstrap into a maintenance database'; END $$;
\endif

-- Generated service credentials: non-default, distinct, at least 24 characters.
SELECT
    length(:'migrator_password') >= 24
    AND length(:'app_password') >= 24
    AND :'migrator_password' <> :'app_password'
    AND :'migrator_password' NOT IN ('triageharbor_migrator', 'triageharbor_app')
    AND :'app_password' NOT IN ('triageharbor_migrator', 'triageharbor_app')
    AS passwords_ok
\gset

\if :passwords_ok
\else
DO $$ BEGIN RAISE EXCEPTION 'restricted-role passwords must be distinct, non-default, and at least 24 characters'; END $$;
\endif

-- Create roles if absent (psql variables are not interpolated inside $$ bodies).
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'triageharbor_migrator') THEN
        CREATE ROLE triageharbor_migrator;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'triageharbor_app') THEN
        CREATE ROLE triageharbor_app;
    END IF;
END
$$;

-- Converge attributes; set or rotate passwords.
ALTER ROLE triageharbor_migrator
    LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
    PASSWORD :'migrator_password';

ALTER ROLE triageharbor_app
    LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
    PASSWORD :'app_password';

-- Remove every membership in either direction (PG16+ grants are per-grantor).
DO $$
DECLARE
    m record;
BEGIN
    FOR m IN
        SELECT r.rolname AS role_name, u.rolname AS member_name, g.rolname AS grantor_name
        FROM pg_auth_members AS am
        JOIN pg_roles AS r ON r.oid = am.roleid
        JOIN pg_roles AS u ON u.oid = am.member
        JOIN pg_roles AS g ON g.oid = am.grantor
        WHERE r.rolname IN ('triageharbor_migrator', 'triageharbor_app')
           OR u.rolname IN ('triageharbor_migrator', 'triageharbor_app')
    LOOP
        EXECUTE format('REVOKE %I FROM %I GRANTED BY %I CASCADE',
                       m.role_name, m.member_name, m.grantor_name);
    END LOOP;
END
$$;

-- Application database ACL: reset, then grant (separate statements fix the order).
SELECT format(
    'REVOKE ALL PRIVILEGES ON DATABASE %I FROM PUBLIC, triageharbor_migrator, triageharbor_app CASCADE',
    current_database())
\gexec

SELECT format(
    'GRANT CONNECT ON DATABASE %I TO triageharbor_migrator, triageharbor_app',
    current_database())
\gexec

-- Maintenance databases: no access for PUBLIC or the restricted roles.
-- template0 is left alone (datallowconn = false).
REVOKE ALL PRIVILEGES ON DATABASE postgres
    FROM PUBLIC, triageharbor_migrator, triageharbor_app CASCADE;
REVOKE ALL PRIVILEGES ON DATABASE template1
    FROM PUBLIC, triageharbor_migrator, triageharbor_app CASCADE;

-- Schema ACL: reset, then grant. Table grants belong to V3, not here.
REVOKE ALL ON SCHEMA public FROM PUBLIC, triageharbor_migrator, triageharbor_app CASCADE;
GRANT USAGE, CREATE ON SCHEMA public TO triageharbor_migrator;
GRANT USAGE ON SCHEMA public TO triageharbor_app;

COMMIT;

\echo 'triageharbor role bootstrap complete'
